using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("PerRoute Recovery")]
[assembly: AssemblyDescription("Puts the network back and rolls PerRoute back, even when PerRoute does not open")]
[assembly: AssemblyCompany("PerRoute")]
[assembly: AssemblyProduct("PerRoute")]
[assembly: AssemblyVersion(ReleaseInfo.Version + ".0")]
[assembly: AssemblyFileVersion(ReleaseInfo.Version + ".0")]

namespace PerRouteRecovery
{
    internal static class Program
    {
        internal const string ProxyKey = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        internal const string RunOnceKey = @"Software\Microsoft\Windows\CurrentVersion\RunOnce";
        internal const string RunOnceValue = "PerRouteRecover";
        internal const string PerRouteProxy = "http=127.0.0.1:17891;https=127.0.0.1:17891;socks=127.0.0.1:17890";
        internal const string PerRouteOverride = "<local>;localhost;127.*;[::1]";
        internal const string EngineService = "ProxiFyreService";

        [STAThread]
        private static int Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            string data = Argument(args, "/data") ?? DefaultDataDirectory();
            Recovery.InstallOverride = Argument(args, "/install");
            try
            {
                if (Has(args, "/engine")) return Recovery.RestoreEngine(new List<string>()) ? 0 : 1;
                if (Has(args, "/cleanup"))
                {
                    List<string> log = new List<string>();
                    Recovery.RestoreUser(data, log);
                    Recovery.RestoreEngine(log);
                    return 0;
                }
                if (Has(args, "/rollback"))
                {
                    string wait = Argument(args, "/wait");
                    int pid;
                    return Recovery.RollBack(wait != null && int.TryParse(wait, out pid) ? pid : 0, true) ? 0 : 1;
                }
                if (Has(args, "/restore"))
                {
                    List<string> log = new List<string>();
                    Recovery.RestoreUser(data, log);
                    if (Recovery.EngineNeedsRestore()) Recovery.Elevate("/engine");
                    return 0;
                }
            }
            catch (Exception error)
            {
                MessageBox.Show(error.Message, "PerRoute Recovery", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
            Application.Run(new RecoveryForm(data));
            return 0;
        }

        internal static bool Has(string[] args, string name)
        {
            foreach (string arg in args) if (String.Equals(arg, name, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        internal static string Argument(string[] args, string name)
        {
            for (int i = 0; i < args.Length - 1; i++) if (String.Equals(args[i], name, StringComparison.OrdinalIgnoreCase)) return args[i + 1];
            return null;
        }

        internal static string DefaultDataDirectory()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PerRoute");
        }

        internal static bool IsAdministrator()
        {
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
                return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
    }

    internal static class Recovery
    {
        [DllImport("wininet.dll", SetLastError = true)]
        private static extern bool InternetSetOption(IntPtr handle, int option, IntPtr buffer, int length);

        internal static string InstallOverride;

        internal static string InstallDirectory
        {
            get { return (InstallOverride ?? AppDomain.CurrentDomain.BaseDirectory).TrimEnd(Path.DirectorySeparatorChar); }
        }
        internal static string RollbackDirectory { get { return InstallDirectory + ".rollback"; } }

        internal static string CurrentProxyServer()
        {
            using (RegistryKey key = Registry.CurrentUser.OpenSubKey(Program.ProxyKey))
            {
                object value = key == null ? null : key.GetValue("ProxyServer");
                return value == null ? "" : value.ToString().Trim();
            }
        }

        internal static bool ProxyIsPerRoutes() { return CurrentProxyServer() == Program.PerRouteProxy; }

        internal static bool RecoveryEntryPresent()
        {
            using (RegistryKey key = Registry.CurrentUser.OpenSubKey(Program.RunOnceKey))
                return key != null && key.GetValue(Program.RunOnceValue) != null;
        }

        internal static string EngineDirectory
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "ProxiFyre"); }
        }

        internal static bool EngineNeedsRestore()
        {
            return File.Exists(Path.Combine(EngineDirectory, "app-config.perroute-backup.json")) ||
                   File.Exists(Path.Combine(EngineDirectory, "app-config.perroute-was-missing"));
        }

        internal static List<string> Leftovers(string data)
        {
            List<string> found = new List<string>();
            bool backup = File.Exists(Path.Combine(data, "system-proxy-backup.json"));
            string server = CurrentProxyServer();
            if (server == Program.PerRouteProxy) found.Add("The Windows proxy still points to PerRoute.");
            else if (backup && server.Length == 0) found.Add("PerRoute's saved proxy settings were never put back.");
            if (RecoveryEntryPresent()) found.Add("A start-up recovery entry is still registered.");
            if (EngineNeedsRestore()) found.Add("The per-app engine still uses PerRoute's app list (needs Administrator).");
            if (LimitedEngine(data) != null) found.Add("PerRoute's own copy of the per-app engine is still running.");
            if (LeftoverCores(data).Count > 0) found.Add("Connection cores are still running.");
            if (File.Exists(Path.Combine(data, "session.active"))) found.Add("A connection was still open when PerRoute stopped.");
            return found;
        }

        internal static void RestoreUser(string data, List<string> log)
        {
            string backupFile = Path.Combine(data, "system-proxy-backup.json");
            string server = CurrentProxyServer();
            bool ours = server == Program.PerRouteProxy;
            if (File.Exists(backupFile))
            {
                if (ours || server.Length == 0)
                {
                    RestoreProxyFromBackup(backupFile);
                    log.Add("The Windows proxy settings PerRoute had saved were put back.");
                }
                else log.Add("The Windows proxy was set by another program after PerRoute; it was left as it is.");
                TryDelete(backupFile);
            }
            else if (ours)
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(Program.ProxyKey, true))
                {
                    if (key != null)
                    {
                        key.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
                        key.DeleteValue("ProxyServer", false);
                        object bypass = key.GetValue("ProxyOverride");
                        if (bypass != null && bypass.ToString() == Program.PerRouteOverride) key.DeleteValue("ProxyOverride", false);
                    }
                }
                log.Add("PerRoute's leftover proxy was switched off.");
            }
            using (RegistryKey key = Registry.CurrentUser.OpenSubKey(Program.RunOnceKey, true))
            {
                if (key != null && key.GetValue(Program.RunOnceValue) != null)
                {
                    key.DeleteValue(Program.RunOnceValue, false);
                    log.Add("The start-up recovery entry was removed.");
                }
            }
            NotifyWindows();
            Process limited = LimitedEngine(data);
            if (limited != null)
            {
                try { limited.Kill(); limited.WaitForExit(5000); log.Add("PerRoute's own copy of the engine was stopped."); } catch { }
                TryDelete(Path.Combine(data, "proxifyre-limited", "perroute.pid"));
            }
            foreach (Process core in LeftoverCores(data))
            {
                try { core.Kill(); core.WaitForExit(3000); } catch { }
            }
            if (File.Exists(Path.Combine(data, "session.active"))) TryDelete(Path.Combine(data, "session.active"));
        }

        private static void RestoreProxyFromBackup(string backupFile)
        {
            Dictionary<string, object> root = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(File.ReadAllText(backupFile));
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(Program.ProxyKey))
            {
                foreach (string name in new[] { "ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL" })
                {
                    object raw;
                    Dictionary<string, object> value = root.TryGetValue(name, out raw) ? raw as Dictionary<string, object> : null;
                    object present = null;
                    if (value == null || !value.TryGetValue("present", out present) || !(present is bool) || !(bool)present)
                    {
                        key.DeleteValue(name, false);
                        continue;
                    }
                    string type = value.ContainsKey("type") ? Convert.ToString(value["type"]) : "REG_SZ";
                    string text = value.ContainsKey("data") ? Convert.ToString(value["data"]) : "";
                    if (type == "REG_DWORD")
                    {
                        string number = text.Trim();
                        int parsed = number.StartsWith("0x", StringComparison.OrdinalIgnoreCase)
                            ? Convert.ToInt32(number.Substring(2), 16) : int.Parse(number);
                        key.SetValue(name, parsed, RegistryValueKind.DWord);
                    }
                    else if (type == "REG_EXPAND_SZ") key.SetValue(name, text, RegistryValueKind.ExpandString);
                    else key.SetValue(name, text, RegistryValueKind.String);
                }
            }
        }

        private static void NotifyWindows()
        {
            try { InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0); InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0); } catch { }
        }

        private static Process LimitedEngine(string data)
        {
            string pidFile = Path.Combine(data, "proxifyre-limited", "perroute.pid");
            try
            {
                if (!File.Exists(pidFile)) return null;
                Process process = Process.GetProcessById(int.Parse(File.ReadAllText(pidFile).Trim()));
                string expected = Path.Combine(data, "proxifyre-limited", "ProxiFyre.exe");
                return String.Equals(process.MainModule.FileName, expected, StringComparison.OrdinalIgnoreCase) ? process : null;
            }
            catch { return null; }
        }

        private static List<Process> LeftoverCores(string data)
        {
            List<Process> cores = new List<Process>();
            foreach (Process process in Process.GetProcessesByName("xray"))
            {
                try
                {
                    string file = process.MainModule.FileName;
                    if (file.StartsWith(InstallDirectory + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) ||
                        file.StartsWith(data + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) cores.Add(process);
                }
                catch { }
            }
            return cores;
        }

        internal static bool RestoreEngine(List<string> log)
        {
            if (!EngineNeedsRestore()) return true;
            if (!Program.IsAdministrator()) { log.Add("Restoring the per-app engine needs Administrator approval."); return false; }
            Run("sc.exe", "stop " + Program.EngineService, 20000);
            WaitForStopped(20000);
            string config = Path.Combine(EngineDirectory, "app-config.json");
            string backup = Path.Combine(EngineDirectory, "app-config.perroute-backup.json");
            string marker = Path.Combine(EngineDirectory, "app-config.perroute-was-missing");
            if (File.Exists(backup))
            {
                if (File.Exists(config)) File.Delete(config);
                File.Move(backup, config);
            }
            else if (File.Exists(marker)) TryDelete(config);
            TryDelete(marker);
            log.Add("The per-app engine was stopped and its previous settings were put back.");
            return true;
        }

        private static void WaitForStopped(int milliseconds)
        {
            DateTime deadline = DateTime.UtcNow.AddMilliseconds(milliseconds);
            while (DateTime.UtcNow < deadline)
            {
                string output = Run("sc.exe", "query " + Program.EngineService, 5000);
                if (output == null || output.Contains("STOPPED") || output.Contains(" 1 ")) return;
                Thread.Sleep(250);
            }
        }

        internal static string InstalledVersion(string directory)
        {
            try { return File.ReadAllText(Path.Combine(directory, "perroute-version.txt")).Trim(); }
            catch { return ""; }
        }

        internal static bool RollbackAvailable()
        {
            return File.Exists(Path.Combine(RollbackDirectory, "PerRoute.exe"));
        }

        internal static bool RollBack(int waitForPid, bool startAfterwards)
        {
            if (!RollbackAvailable()) { MessageBox.Show("No previous version is kept on this computer.", "PerRoute Recovery"); return false; }
            if (!Program.IsAdministrator()) { Elevate("/rollback" + (waitForPid > 0 ? " /wait " + waitForPid : "")); return true; }
            if (!IsRunningFromCopy()) { RunFromCopy("/rollback" + (waitForPid > 0 ? " /wait " + waitForPid : "")); return true; }
            if (waitForPid > 0)
            {
                try { Process.GetProcessById(waitForPid).WaitForExit(30000); } catch { }
            }
            foreach (Process process in Process.GetProcessesByName("PerRoute"))
            {
                try
                {
                    if (process.MainModule.FileName.StartsWith(InstallDirectory + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                    {
                        if (!process.WaitForExit(5000)) { process.Kill(); process.WaitForExit(5000); }
                    }
                }
                catch { }
            }
            string install = InstallDirectory;
            string rollback = RollbackDirectory;
            string current = InstalledVersion(install);
            string previous = InstalledVersion(rollback);
            string failed = install + ".failed";
            try
            {
                if (Directory.Exists(failed)) Directory.Delete(failed, true);
                Directory.Move(install, failed);
                try { Directory.Move(rollback, install); }
                catch { Directory.Move(failed, install); throw; }
            }
            catch (Exception error)
            {
                MessageBox.Show("The previous version could not be put back; nothing was changed.\r\n\r\n" + error.Message, "PerRoute Recovery", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return false;
            }
            try { Directory.Delete(failed, true); } catch { }
            try
            {
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (RegistryKey key = machine.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\PerRoute", true))
                {
                    if (key != null && previous.Length > 0) key.SetValue("DisplayVersion", previous);
                }
            }
            catch { }
            if (startAfterwards)
            {
                try { Process.Start("explorer.exe", "\"" + Path.Combine(install, "PerRoute.exe") + "\""); } catch { }
            }
            MessageBox.Show("PerRoute " + (previous.Length > 0 ? previous : "") + " is back" + (current.Length > 0 ? ", in place of " + current : "") + ". Your settings were kept.",
                "PerRoute Recovery", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return true;
        }

        private static string CopyMarker = "PerRouteRecovery-copy-";

        private static bool IsRunningFromCopy()
        {
            return AppDomain.CurrentDomain.FriendlyName.StartsWith(CopyMarker, StringComparison.OrdinalIgnoreCase) ||
                   Path.GetFileName(Application.ExecutablePath).StartsWith(CopyMarker, StringComparison.OrdinalIgnoreCase);
        }

        private static void RunFromCopy(string arguments)
        {
            string copy = Path.Combine(Path.GetTempPath(), CopyMarker + Guid.NewGuid().ToString("N") + ".exe");
            File.Copy(Application.ExecutablePath, copy);
            ProcessStartInfo start = new ProcessStartInfo(copy, arguments + " /install \"" + InstallDirectory + "\"") { UseShellExecute = false };
            Process.Start(start);
        }

        internal static void Elevate(string arguments)
        {
            ProcessStartInfo start = new ProcessStartInfo(Application.ExecutablePath, arguments + " /data \"" + Program.DefaultDataDirectory() + "\"")
            {
                UseShellExecute = true,
                Verb = "runas"
            };
            try { Process.Start(start); }
            catch (System.ComponentModel.Win32Exception) { }
        }

        internal static string Run(string file, string arguments, int timeout)
        {
            try
            {
                ProcessStartInfo start = new ProcessStartInfo(file, arguments)
                {
                    UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
                };
                using (Process process = Process.Start(start))
                {
                    string output = process.StandardOutput.ReadToEnd();
                    process.WaitForExit(timeout);
                    return output;
                }
            }
            catch { return null; }
        }

        private static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { }
        }
    }

    internal sealed class RecoveryForm : Form
    {
        private readonly string data;
        private readonly Label networkStatus;
        private readonly Label versionStatus;
        private readonly Button restoreButton;
        private readonly Button rollbackButton;
        private readonly TextBox log;
        private readonly Color background = Color.FromArgb(30, 32, 32);
        private readonly Color panel = Color.FromArgb(39, 42, 41);
        private readonly Color lime = Color.FromArgb(216, 242, 74);
        private readonly Color muted = Color.FromArgb(170, 176, 170);

        internal RecoveryForm(string data)
        {
            this.data = data;
            Text = "PerRoute Recovery";
            ClientSize = new Size(560, 470);
            MinimumSize = MaximumSize = Size;
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            BackColor = background;
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9F);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            PictureBox logo = new PictureBox { Location = new Point(28, 24), Size = new Size(44, 44), SizeMode = PictureBoxSizeMode.Zoom };
            try { logo.Image = Icon.ExtractAssociatedIcon(Application.ExecutablePath).ToBitmap(); } catch { }
            Controls.Add(logo);
            Controls.Add(new Label { Text = "PerRoute Recovery", AutoSize = true, Font = new Font("Segoe UI", 17F, FontStyle.Bold), Location = new Point(84, 22) });
            Controls.Add(new Label { Text = "Puts the network back when PerRoute cannot. Only PerRoute's own changes are undone.", AutoSize = true, ForeColor = muted, Location = new Point(86, 54) });

            Panel network = new Panel { BackColor = panel, Location = new Point(28, 92), Size = new Size(504, 140) };
            Controls.Add(network);
            network.Controls.Add(new Label { Text = "Network settings", AutoSize = true, Font = new Font("Segoe UI", 11F, FontStyle.Bold), Location = new Point(16, 12) });
            networkStatus = new Label { Location = new Point(18, 40), Size = new Size(470, 56), ForeColor = muted };
            network.Controls.Add(networkStatus);
            restoreButton = new Button { Text = "Restore network settings", Location = new Point(16, 98), Size = new Size(200, 30), FlatStyle = FlatStyle.Flat, BackColor = lime, ForeColor = Color.Black };
            restoreButton.FlatAppearance.BorderSize = 0;
            restoreButton.Click += delegate { Restore(); };
            network.Controls.Add(restoreButton);

            Panel version = new Panel { BackColor = panel, Location = new Point(28, 244), Size = new Size(504, 104) };
            Controls.Add(version);
            version.Controls.Add(new Label { Text = "Previous version", AutoSize = true, Font = new Font("Segoe UI", 11F, FontStyle.Bold), Location = new Point(16, 12) });
            versionStatus = new Label { Location = new Point(18, 40), Size = new Size(470, 24), ForeColor = muted };
            version.Controls.Add(versionStatus);
            rollbackButton = new Button { Text = "Go back", Location = new Point(16, 64), Size = new Size(200, 30), FlatStyle = FlatStyle.Flat, ForeColor = Color.White };
            rollbackButton.FlatAppearance.BorderColor = muted;
            rollbackButton.Click += delegate { RollBack(); };
            version.Controls.Add(rollbackButton);

            log = new TextBox { Location = new Point(28, 360), Size = new Size(504, 64), Multiline = true, ReadOnly = true, BorderStyle = BorderStyle.None, BackColor = background, ForeColor = muted };
            Controls.Add(log);
            Button start = new Button { Text = "Open PerRoute", Location = new Point(332, 430), Size = new Size(110, 28), FlatStyle = FlatStyle.Flat, ForeColor = Color.White };
            start.FlatAppearance.BorderColor = muted;
            start.Click += delegate { try { Process.Start(Path.Combine(Recovery.InstallDirectory, "PerRoute.exe")); } catch (Exception error) { MessageBox.Show(error.Message); } };
            Controls.Add(start);
            Button close = new Button { Text = "Close", Location = new Point(452, 430), Size = new Size(80, 28), FlatStyle = FlatStyle.Flat, ForeColor = Color.White };
            close.FlatAppearance.BorderColor = muted;
            close.Click += delegate { Close(); };
            Controls.Add(close);
            Refresh_();
        }

        private void Refresh_()
        {
            List<string> leftovers = Recovery.Leftovers(data);
            networkStatus.Text = leftovers.Count == 0 ? "Clean. PerRoute has left nothing behind." : String.Join("\r\n", leftovers.ToArray());
            networkStatus.ForeColor = leftovers.Count == 0 ? lime : Color.FromArgb(242, 184, 75);
            restoreButton.Enabled = leftovers.Count > 0;
            string current = Recovery.InstalledVersion(Recovery.InstallDirectory);
            string previous = Recovery.InstalledVersion(Recovery.RollbackDirectory);
            bool available = Recovery.RollbackAvailable();
            versionStatus.Text = available
                ? "PerRoute " + (previous.Length > 0 ? previous : "(previous)") + " is kept. Going back keeps your settings." + (current.Length > 0 ? " Installed now: " + current + "." : "")
                : "No previous version is kept on this computer.";
            rollbackButton.Enabled = available;
            rollbackButton.Text = available && previous.Length > 0 ? "Go back to " + previous : "Go back";
        }

        private void Restore()
        {
            if (Process.GetProcessesByName("PerRoute").Length > 0 &&
                MessageBox.Show(this, "PerRoute is running. Close it first so it does not change the settings again.\r\n\r\nRestore anyway?", "PerRoute Recovery",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            List<string> lines = new List<string>();
            try { Recovery.RestoreUser(data, lines); }
            catch (Exception error) { lines.Add("Could not restore the proxy settings: " + error.Message); }
            if (Recovery.EngineNeedsRestore())
            {
                if (Program.IsAdministrator()) Recovery.RestoreEngine(lines);
                else { lines.Add("Asking for Administrator approval to restore the per-app engine…"); Recovery.Elevate("/engine"); Thread.Sleep(1500); }
            }
            log.Text = lines.Count == 0 ? "Nothing needed restoring." : String.Join("\r\n", lines.ToArray());
            Refresh_();
        }

        private void RollBack()
        {
            if (MessageBox.Show(this, "Go back to the previous version of PerRoute? Your servers, apps and settings are kept. PerRoute will be closed.",
                    "PerRoute Recovery", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            Recovery.RollBack(0, true);
            Close();
        }
    }
}
