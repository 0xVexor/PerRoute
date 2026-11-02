using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("PerRoute Uninstaller")]
[assembly: AssemblyDescription("Uninstaller for PerRoute and the PerRoute browser extension")]
[assembly: AssemblyCompany("PerRoute")]
[assembly: AssemblyProduct("PerRoute")]
[assembly: AssemblyVersion(ReleaseInfo.Version + ".0")]
[assembly: AssemblyFileVersion(ReleaseInfo.Version + ".0")]

namespace PerRouteSetup
{
    internal static class UninstallerProgram
    {
        private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\PerRoute";
        private const string HostName = "com.perroute.browser";
        private const string ExplorerMenuVerb = "PerRoute.Route";
        private const string BridgeFirewallRule = "PerRoute LAN Bridge";
        private static readonly string[] ExplorerMenuClasses = { "exefile", "lnkfile" };
        private static readonly string[] NativeMessagingKeys = {
            @"Software\Google\Chrome\NativeMessagingHosts",
            @"Software\Chromium\NativeMessagingHosts",
            @"Software\Microsoft\Edge\NativeMessagingHosts",
            @"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts",
            @"Software\Vivaldi\NativeMessagingHosts",
        };

        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            string self = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
            string appDirectory = null;
            string browserDirectory = null;
            try
            {
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (RegistryKey key = machine.OpenSubKey(UninstallKey))
                {
                    appDirectory = key == null ? null : key.GetValue("InstallLocation") as string;
                    browserDirectory = key == null ? null : key.GetValue("BrowserLocation") as string;
                }
            }
            catch { }
            if (appDirectory == null && File.Exists(Path.Combine(self, "PerRoute.exe"))) appDirectory = self;
            if (browserDirectory == null && File.Exists(Path.Combine(self, "perroute-browser-host.exe"))) browserDirectory = self;

            List<string> parts = new List<string>();
            if (appDirectory != null) parts.Add("the PerRoute app");
            if (browserDirectory != null) parts.Add("the browser runtime");
            string what = parts.Count == 0 ? "PerRoute" : String.Join(" and ", parts.ToArray());
            DialogResult answer = MessageBox.Show(
                "Remove " + what + " from this computer?" +
                (appDirectory != null ? "\r\n\r\nThe shared per-app network driver will be kept for safety." : "") +
                (browserDirectory != null ? "\r\n\r\nAfterwards, remove the PerRoute extension from your browser's extensions page." : ""),
                "Uninstall PerRoute",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question,
                MessageBoxDefaultButton.Button2);
            if (answer != DialogResult.Yes) return;

            List<string> folders = new List<string>();
            if (appDirectory != null)
            {
                StopProcesses(appDirectory, "PerRoute");
                string recovery = Path.Combine(appDirectory, "PerRouteRecovery.exe");
                if (File.Exists(recovery))
                {
                    try
                    {
                        using (Process restore = Process.Start(new ProcessStartInfo(recovery, "/cleanup") { UseShellExecute = false, CreateNoWindow = true }))
                            restore.WaitForExit(60000);
                    }
                    catch { }
                }
                TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Programs", "PerRoute.lnk"));
                TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Programs", "PerRoute Recovery.lnk"));
                TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory), "PerRoute.lnk"));
                RemoveExplorerMenu();
                RemoveBridgeFirewallRule();
                folders.Add(appDirectory);
                folders.Add(appDirectory + ".rollback");
            }
            if (browserDirectory != null)
            {
                StopProcesses(browserDirectory, "perroute-browser-host", "xray");
                foreach (RegistryView view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
                {
                    try
                    {
                        using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                            foreach (string key in NativeMessagingKeys)
                                try { machine.DeleteSubKeyTree(key + "\\" + HostName, false); } catch { }
                    }
                    catch { }
                }
                folders.Add(browserDirectory);
            }
            try
            {
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                    machine.DeleteSubKeyTree(UninstallKey, false);
            }
            catch { }

            // This program runs from one of the folders: remove them after it exits.
            string cleanup = Path.Combine(Path.GetTempPath(), "PerRoute-cleanup-" + Guid.NewGuid().ToString("N") + ".cmd");
            string script = "@echo off\r\ntimeout /t 2 /nobreak >nul\r\n";
            foreach (string folder in folders) script += "rmdir /s /q \"" + folder + "\"\r\n";
            script += "del /f /q \"%~f0\"\r\n";
            File.WriteAllText(cleanup, script);
            Process.Start(new ProcessStartInfo("cmd.exe", "/c \"\"" + cleanup + "\"\"") {
                CreateNoWindow = true,
                UseShellExecute = false,
                WindowStyle = ProcessWindowStyle.Hidden
            });
            MessageBox.Show(
                "PerRoute has been removed." + (browserDirectory != null ? "\r\n\r\nRemove the PerRoute extension from your browser's extensions page to finish." : ""),
                "Uninstall complete", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        private static void StopProcesses(string directory, params string[] names)
        {
            string prefix = directory.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            foreach (string name in names)
                foreach (Process process in Process.GetProcessesByName(name))
                {
                    try
                    {
                        if (!process.MainModule.FileName.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) continue;
                        if (name == "PerRoute") process.CloseMainWindow();
                        if (name != "PerRoute" || !process.WaitForExit(8000)) { process.Kill(); process.WaitForExit(5000); }
                    }
                    catch { }
                }
        }

        private static void RemoveExplorerMenu()
        {
            foreach (string fileClass in ExplorerMenuClasses)
                try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\" + fileClass + @"\shell\" + ExplorerMenuVerb, false); } catch { }
            string signedIn = SessionUserSid();
            string current = null;
            try { current = WindowsIdentity.GetCurrent().User.Value; } catch { }
            if (signedIn == null || String.Equals(signedIn, current, StringComparison.OrdinalIgnoreCase)) return;
            foreach (string fileClass in ExplorerMenuClasses)
            {
                try { Registry.Users.DeleteSubKeyTree(signedIn + @"_Classes\" + fileClass + @"\shell\" + ExplorerMenuVerb, false); } catch { }
                try { Registry.Users.DeleteSubKeyTree(signedIn + @"\Software\Classes\" + fileClass + @"\shell\" + ExplorerMenuVerb, false); } catch { }
            }
        }

        private static string SessionUserSid()
        {
            try
            {
                int session = Process.GetCurrentProcess().SessionId;
                string user = SessionText(session, 5);
                if (String.IsNullOrEmpty(user)) return null;
                string domain = SessionText(session, 7);
                NTAccount account = String.IsNullOrEmpty(domain) ? new NTAccount(user) : new NTAccount(domain, user);
                return ((SecurityIdentifier)account.Translate(typeof(SecurityIdentifier))).Value;
            }
            catch { return null; }
        }

        private static string SessionText(int session, int infoClass)
        {
            IntPtr buffer;
            int bytes;
            if (!WTSQuerySessionInformation(IntPtr.Zero, session, infoClass, out buffer, out bytes)) return null;
            try { return Marshal.PtrToStringUni(buffer); }
            finally { WTSFreeMemory(buffer); }
        }

        [DllImport("wtsapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool WTSQuerySessionInformation(IntPtr server, int session, int infoClass, out IntPtr buffer, out int bytes);

        [DllImport("wtsapi32.dll")]
        private static extern void WTSFreeMemory(IntPtr memory);

        private static void RemoveBridgeFirewallRule()
        {
            try
            {
                string netsh = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "netsh.exe");
                using (Process process = Process.Start(new ProcessStartInfo(netsh, "advfirewall firewall delete rule name=\"" + BridgeFirewallRule + "\"") { UseShellExecute = false, CreateNoWindow = true }))
                    if (!process.WaitForExit(30000)) process.Kill();
            }
            catch { }
        }

        private static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { }
        }
    }
}
