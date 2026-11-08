using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Resources;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("PerRoute Setup")]
[assembly: AssemblyDescription("Complete system installer for PerRoute")]
[assembly: AssemblyCompany("Ali Mirzad")]
[assembly: AssemblyProduct("PerRoute")]
[assembly: AssemblyVersion("3.5.1.0")]
[assembly: AssemblyFileVersion("3.5.1.0")]

namespace PerRouteSetup
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            if (!Environment.Is64BitOperatingSystem)
            {
                MessageBox.Show("PerRoute requires 64-bit Windows 10 or 11.", "Unsupported Windows version",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            if (!IsAdministrator())
            {
                MessageBox.Show("Administrator approval is required to install the per-app network driver.",
                    "Administrator access required", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            Application.Run(new InstallerForm());
        }

        private static bool IsAdministrator()
        {
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
                return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
    }

    internal sealed class InstallerForm : Form
    {
        private const string Version = "3.5.1";
        private const string ResourceName = "PerRoute.Payload";
        private const string ProxiFyreResourceName = "PerRoute.ProxiFyreSetup";
        private const string VisualCppResourceName = "PerRoute.VisualCppRuntime";
        private const string WindowsPacketFilterResourceName = "PerRoute.WindowsPacketFilter";
        private const string ProxiFyreFileName = "ProxiFyre-2.6.2-win-x64-setup.exe";
        private const string VisualCppFileName = "VC_redist.x64.exe";
        private const string WindowsPacketFilterFileName = "Windows.Packet.Filter.3.6.2.1.x64.msi";
        private const string ProxiFyreSha256 = "952CA0F40571194E7DFCD922A92BC3BEF1D0759A90C602E8632A9AF958C13B73";
        private const string VisualCppSha256 = "CC0FF0EB1DC3F5188AE6300FAEF32BF5BEEBA4BDD6E8E445A9184072096B713B";
        private const string WindowsPacketFilterSha256 = "9C388C0B7F189F7FA98720BAE2CAECF7D64F30910838B80B438ECF8956B8502C";
        private readonly Color background = Color.FromArgb(17, 18, 28);
        private readonly Color panel = Color.FromArgb(29, 31, 46);
        private readonly Color violet = Color.FromArgb(132, 102, 255);
        private readonly Color muted = Color.FromArgb(164, 168, 188);
        private readonly Label statusLabel;
        private readonly ProgressBar progressBar;
        private readonly Button installButton;
        private readonly Button cancelButton;
        private readonly Button browseButton;
        private readonly TextBox locationBox;
        private readonly CheckBox desktopShortcut;
        private readonly CheckBox launchAfterInstall;

        internal InstallerForm()
        {
            Text = "PerRoute Setup";
            ClientSize = new Size(590, 420);
            MinimumSize = MaximumSize = Size;
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            BackColor = background;
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9F);
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);

            Panel accent = new Panel { BackColor = violet, Location = new Point(0, 0), Size = new Size(590, 5) };
            Controls.Add(accent);

            // PerRoute's logo, taken from this installer's own icon so it always matches the application.
            PictureBox logo = new PictureBox {
                Location = new Point(34, 34), Size = new Size(54, 54), SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.Transparent
            };
            try { logo.Image = new Icon(Icon, new Size(256, 256)).ToBitmap(); } catch { }
            Controls.Add(logo);

            Label title = new Label {
                Text = "Install PerRoute", AutoSize = true,
                Font = new Font("Segoe UI", 21F, FontStyle.Bold),
                Location = new Point(105, 32), ForeColor = Color.White
            };
            Controls.Add(title);

            Label subtitle = new Label {
                Text = "Private routing for the apps you choose  •  Version " + Version,
                AutoSize = true, Location = new Point(108, 72), ForeColor = muted
            };
            Controls.Add(subtitle);

            Panel card = new Panel {
                BackColor = panel, Location = new Point(34, 112), Size = new Size(522, 196)
            };
            Controls.Add(card);

            Label locationTitle = new Label {
                Text = "INSTALL LOCATION", AutoSize = true,
                Font = new Font("Segoe UI", 8F, FontStyle.Bold),
                ForeColor = Color.FromArgb(182, 165, 255), Location = new Point(18, 17)
            };
            card.Controls.Add(locationTitle);

            locationBox = new TextBox {
                Text = DefaultInstallDirectory(), ReadOnly = false, BorderStyle = BorderStyle.FixedSingle,
                BackColor = Color.FromArgb(22, 23, 35), ForeColor = Color.White,
                Location = new Point(21, 42), Size = new Size(388, 28)
            };
            card.Controls.Add(locationBox);

            browseButton = CreateButton("Browse…", Color.FromArgb(55, 58, 76), new Point(418, 39), new Size(83, 31));
            browseButton.Click += BrowseLocation;
            card.Controls.Add(browseButton);

            desktopShortcut = new CheckBox {
                Text = "Create a desktop shortcut", Checked = true, AutoSize = true,
                ForeColor = Color.White, Location = new Point(21, 89)
            };
            card.Controls.Add(desktopShortcut);

            launchAfterInstall = new CheckBox {
                Text = "Launch PerRoute after installation", Checked = true, AutoSize = true,
                ForeColor = Color.White, Location = new Point(21, 119)
            };
            card.Controls.Add(launchAfterInstall);

            Label bundleNote = new Label {
                Text = "Includes ProxiFyre, Windows Packet Filter and Visual C++ Runtime.",
                AutoSize = true, Location = new Point(21, 154), ForeColor = muted,
                Font = new Font("Segoe UI", 8F)
            };
            card.Controls.Add(bundleNote);

            statusLabel = new Label {
                Text = "Administrator mode active. No further setup is required after installation.",
                AutoEllipsis = true, Location = new Point(35, 320), Size = new Size(350, 18), ForeColor = muted
            };
            Controls.Add(statusLabel);

            progressBar = new ProgressBar {
                Location = new Point(35, 352), Size = new Size(350, 8),
                Style = ProgressBarStyle.Continuous, Minimum = 0, Maximum = 100, Value = 0
            };
            Controls.Add(progressBar);

            installButton = CreateButton("Install", violet, new Point(398, 339), new Size(100, 38));
            installButton.Click += InstallClicked;
            Controls.Add(installButton);

            cancelButton = CreateButton("Cancel", Color.FromArgb(55, 58, 76), new Point(505, 339), new Size(70, 38));
            cancelButton.Click += delegate { Close(); };
            Controls.Add(cancelButton);

            Label credit = new Label {
                Text = "Created by Ali Mirzad", AutoSize = true,
                Font = new Font("Segoe UI", 7.5F, FontStyle.Bold),
                ForeColor = Color.FromArgb(154, 137, 235), Location = new Point(35, 390)
            };
            Controls.Add(credit);
        }

        private Button CreateButton(string text, Color color, Point location, Size size)
        {
            Button button = new Button {
                Text = text, Location = location, Size = size,
                FlatStyle = FlatStyle.Flat, BackColor = color, ForeColor = Color.White,
                Cursor = Cursors.Hand, Font = new Font("Segoe UI", 9F, FontStyle.Bold)
            };
            button.FlatAppearance.BorderSize = 0;
            return button;
        }

        private void BrowseLocation(object sender, EventArgs e)
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Choose a parent folder. A PerRoute folder will be created inside it.";
                dialog.ShowNewFolderButton = true;
                string current = locationBox.Text.Trim();
                string parent = null;
                try { parent = Path.GetDirectoryName(Path.GetFullPath(current)); } catch { }
                if (!String.IsNullOrEmpty(parent) && Directory.Exists(parent)) dialog.SelectedPath = parent;
                if (dialog.ShowDialog(this) == DialogResult.OK)
                {
                    locationBox.Text = String.Equals(Path.GetFileName(dialog.SelectedPath), "PerRoute", StringComparison.OrdinalIgnoreCase)
                        ? dialog.SelectedPath
                        : Path.Combine(dialog.SelectedPath, "PerRoute");
                }
            }
        }

        private async void InstallClicked(object sender, EventArgs e)
        {
            string selectedDirectory;
            try
            {
                selectedDirectory = NormalizeInstallDirectory(locationBox.Text);
                ValidateInstallDirectory(selectedDirectory);
                locationBox.Text = selectedDirectory;
            }
            catch (Exception error)
            {
                MessageBox.Show(this, error.Message, "Invalid installation location",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                locationBox.Focus();
                return;
            }

            installButton.Enabled = false;
            cancelButton.Enabled = false;
            browseButton.Enabled = false;
            locationBox.Enabled = false;
            desktopShortcut.Enabled = false;
            launchAfterInstall.Enabled = false;
            UseWaitCursor = true;
            statusLabel.Text = "Preparing application files…";
            progressBar.Value = 2;

            Progress<InstallProgress> progress = new Progress<InstallProgress>(delegate(InstallProgress update) {
                progressBar.Value = Math.Max(0, Math.Min(100, update.Percent));
                statusLabel.Text = update.Status;
            });

            try {
                InstallationResult result = await Task.Run(delegate { return Install(progress, desktopShortcut.Checked, selectedDirectory); });
                progressBar.Value = 100;
                statusLabel.Text = "PerRoute was installed successfully.";
                UseWaitCursor = false;
                ResetStateKeyPermissions();
                // Through Explorer, so PerRoute runs as the signed-in user instead of inheriting this installer's
                // Administrator rights (which would also give its files Administrator-only permissions).
                if (launchAfterInstall.Checked && !result.RestartRequired) Process.Start("explorer.exe", "\"" + result.Executable + "\"");
                string completion = "PerRoute " + Version + " and its per-app engine are ready to use.";
                if (result.RestartRequired) completion += "\r\n\r\nWindows must be restarted before Smart per-app mode can be used.";
                MessageBox.Show(this, completion, "Installation complete",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                Close();
            }
            catch (Exception error)
            {
                UseWaitCursor = false;
                installButton.Enabled = true;
                cancelButton.Enabled = true;
                browseButton.Enabled = true;
                locationBox.Enabled = true;
                desktopShortcut.Enabled = true;
                launchAfterInstall.Enabled = true;
                statusLabel.Text = "Installation failed. Review the error and try again.";
                MessageBox.Show(this, error.Message, "PerRoute could not be installed",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static void ResetStateKeyPermissions()
        {
            try
            {
                string key = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PerRoute", "state.bin.key");
                if (!File.Exists(key)) return;
                ProcessStartInfo start = new ProcessStartInfo("icacls.exe", "\"" + key + "\" /reset");
                start.UseShellExecute = false;
                start.CreateNoWindow = true;
                using (Process process = Process.Start(start)) process.WaitForExit(10000);
            }
            catch (Exception) { }
        }

        private static InstallationResult Install(IProgress<InstallProgress> progress, bool createDesktopShortcut, string installDirectory)
        {
            string target = NormalizeInstallDirectory(installDirectory);
            ValidateInstallDirectory(target);
            string parent = Path.GetDirectoryName(target);
            string stage = Path.Combine(Path.GetTempPath(), "PerRoute-install-" + Guid.NewGuid().ToString("N"));
            string applicationStage = Path.Combine(stage, "application");
            string dependencyStage = Path.Combine(stage, "dependencies");
            string backup = target + ".previous";
            Directory.CreateDirectory(stage);
            Directory.CreateDirectory(applicationStage);
            Directory.CreateDirectory(dependencyStage);
            Directory.CreateDirectory(parent);

            try
            {
                progress.Report(new InstallProgress(5, "Preparing the complete offline package…"));
                PrepareDependencyBundle(dependencyStage);
                ExtractPayload(applicationStage, progress);
                string[] extractedDirectories = Directory.GetDirectories(applicationStage);
                if (extractedDirectories.Length != 1) throw new InvalidDataException("The installer payload is incomplete.");
                string extracted = extractedDirectories[0];

                progress.Report(new InstallProgress(79, "Installing the per-app network engine and driver."));
                // The runtime and the packet filter driver are installed explicitly: relying on the engine's own
                // setup to chain them leaves a machine without them when that step is skipped or fails quietly.
                bool restartRequired = InstallPrerequisite(Path.Combine(dependencyStage, VisualCppFileName), "/install /quiet /norestart", "The Visual C++ Runtime");
                restartRequired |= InstallPrerequisite(Path.Combine(dependencyStage, WindowsPacketFilterFileName), null, "The Windows Packet Filter driver");
                restartRequired |= InstallPerAppEngine(dependencyStage);
                VerifyPerAppEngine();
                RemoveDependencyShortcuts();

                progress.Report(new InstallProgress(88, "Installing PerRoute for all users…"));
                string rollback = target + ".rollback";
                string previousVersion = InstalledVersion(target);
                if (Directory.Exists(backup)) Directory.Delete(backup, true);
                bool backedUp = false;
                try
                {
                    if (Directory.Exists(target)) {
                        Directory.Move(target, backup);
                        backedUp = true;
                    }
                    Directory.Move(extracted, target);
                    File.WriteAllText(Path.Combine(target, "perroute-version.txt"), Version);
                }
                catch
                {
                    if (!Directory.Exists(target) && Directory.Exists(backup)) Directory.Move(backup, target);
                    throw;
                }
                // The version that was replaced is kept for a rollback (only the latest one), unless it is this
                // same version being installed again.
                if (backedUp && Directory.Exists(backup))
                {
                    try
                    {
                        if (previousVersion.Length > 0 && previousVersion != Version && File.Exists(Path.Combine(backup, "PerRoute.exe")))
                        {
                            if (Directory.Exists(rollback)) Directory.Delete(rollback, true);
                            File.WriteAllText(Path.Combine(backup, "perroute-version.txt"), previousVersion);
                            Directory.Move(backup, rollback);
                        }
                        else Directory.Delete(backup, true);
                    }
                    catch { try { if (Directory.Exists(backup)) Directory.Delete(backup, true); } catch { } }
                }

                progress.Report(new InstallProgress(91, "Creating shortcuts…"));
                string executable = Path.Combine(target, "PerRoute.exe");
                CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu),
                    "Programs", "PerRoute.lnk"), executable);
                string recovery = Path.Combine(target, "PerRouteRecovery.exe");
                if (File.Exists(recovery)) CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu),
                    "Programs", "PerRoute Recovery.lnk"), recovery);
                string desktopLink = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory), "PerRoute.lnk");
                if (createDesktopShortcut) CreateShortcut(desktopLink, executable);
                else if (File.Exists(desktopLink)) File.Delete(desktopLink);
                RegisterUninstaller(target);
                progress.Report(new InstallProgress(100, "Installation complete."));
                return new InstallationResult(executable, restartRequired);
            }
            catch (IOException error)
            {
                throw new IOException("PerRoute files could not be updated. Close PerRoute if it is running, then try again.\r\n\r\n" + error.Message, error);
            }
            finally
            {
                try { if (Directory.Exists(stage)) Directory.Delete(stage, true); } catch { }
            }
        }

        private static void ExtractPayload(string destination, IProgress<InstallProgress> progress)
        {
            Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName);
            if (payload == null) throw new MissingManifestResourceException("The embedded application payload was not found.");
            using (payload)
            using (ZipArchive archive = new ZipArchive(payload, ZipArchiveMode.Read))
            {
                long total = 0;
                foreach (ZipArchiveEntry item in archive.Entries) total += item.Length;
                long extracted = 0;
                string root = Path.GetFullPath(destination) + Path.DirectorySeparatorChar;
                foreach (ZipArchiveEntry item in archive.Entries)
                {
                    string output = Path.GetFullPath(Path.Combine(destination, item.FullName.Replace('/', Path.DirectorySeparatorChar)));
                    if (!output.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Unsafe path in installer payload.");
                    if (String.IsNullOrEmpty(item.Name)) {
                        Directory.CreateDirectory(output);
                        continue;
                    }
                    Directory.CreateDirectory(Path.GetDirectoryName(output));
                    using (Stream input = item.Open())
                    using (FileStream file = new FileStream(output, FileMode.Create, FileAccess.Write, FileShare.None)) input.CopyTo(file);
                    extracted += item.Length;
                    int percent = total == 0 ? 73 : 7 + (int)(66L * extracted / total);
                    progress.Report(new InstallProgress(percent, "Extracting " + item.Name + "…"));
                }
            }
        }

        private static void PrepareDependencyBundle(string directory)
        {
            ExtractVerifiedResource(ProxiFyreResourceName, Path.Combine(directory, ProxiFyreFileName), ProxiFyreSha256);
            ExtractVerifiedResource(VisualCppResourceName, Path.Combine(directory, VisualCppFileName), VisualCppSha256);
            ExtractVerifiedResource(WindowsPacketFilterResourceName, Path.Combine(directory, WindowsPacketFilterFileName), WindowsPacketFilterSha256);
        }

        private static void ExtractVerifiedResource(string resourceName, string destination, string expectedSha256)
        {
            using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName))
            {
                if (input == null) throw new MissingManifestResourceException("A bundled installer dependency is missing: " + resourceName);
                using (FileStream output = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.None))
                    input.CopyTo(output);
            }
            using (SHA256 sha256 = SHA256.Create())
            using (FileStream input = File.OpenRead(destination))
            {
                string actual = BitConverter.ToString(sha256.ComputeHash(input)).Replace("-", "");
                if (!String.Equals(actual, expectedSha256, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("A bundled installer dependency failed integrity verification.");
            }
        }

        /// <summary>Runs a bundled installer silently. Returns true when Windows must restart to finish it.</summary>
        private static bool InstallPrerequisite(string file, string arguments, string name)
        {
            bool msi = file.EndsWith(".msi", StringComparison.OrdinalIgnoreCase);
            string program = msi ? Path.Combine(Environment.SystemDirectory, "msiexec.exe") : file;
            string parameters = msi ? "/i \"" + file + "\" /qn /norestart" : arguments;
            ProcessStartInfo start = new ProcessStartInfo(program, parameters) {
                WorkingDirectory = Path.GetDirectoryName(file),
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using (Process process = Process.Start(start))
            {
                process.WaitForExit();
                // 1638: a newer version is already installed; 3010 and 1641: installed, restart needed.
                if (process.ExitCode == 0 || process.ExitCode == 1638) return false;
                if (process.ExitCode == 3010 || process.ExitCode == 1641) return true;
                throw new InvalidOperationException(name + " could not be installed (code " + process.ExitCode + ").");
            }
        }

        private static bool InstallPerAppEngine(string directory)
        {
            string setup = Path.Combine(directory, ProxiFyreFileName);
            ProcessStartInfo start = new ProcessStartInfo(setup, "/install /quiet /norestart") {
                WorkingDirectory = directory,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using (Process process = Process.Start(start))
            {
                process.WaitForExit();
                if (process.ExitCode == 0) return false;
                if (process.ExitCode == 3010 || process.ExitCode == 1641) return true;
                throw new InvalidOperationException("The bundled per-app engine installer failed with code " + process.ExitCode + ".");
            }
        }

        private static void VerifyPerAppEngine()
        {
            string executable = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "ProxiFyre", "ProxiFyre.exe");
            if (!File.Exists(executable))
                throw new InvalidDataException("The per-app engine did not finish installing.");
            using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
            using (RegistryKey service = machine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\ProxiFyreService"))
                if (service == null) throw new InvalidDataException("The ProxiFyre Windows service was not registered.");
            using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
            using (RegistryKey driver = machine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\ndisrd"))
                if (driver == null) throw new InvalidDataException("The Windows Packet Filter driver was not registered.");
        }

        private static void RemoveDependencyShortcuts()
        {
            string[] roots = {
                Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu),
                Environment.GetFolderPath(Environment.SpecialFolder.StartMenu),
                Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory),
                Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory)
            };
            string[] productNames = { "proxifyre", "windows packet filter", "winpkfilter", "ndisapi" };
            foreach (string root in roots)
            {
                if (String.IsNullOrEmpty(root) || !Directory.Exists(root)) continue;
                try
                {
                    foreach (string shortcut in Directory.EnumerateFiles(root, "*.lnk", SearchOption.AllDirectories))
                    {
                        string normalized = shortcut.ToLowerInvariant();
                        if (Array.Exists(productNames, normalized.Contains))
                            try { File.Delete(shortcut); } catch { }
                    }
                }
                catch { }
            }
        }

        private static void CreateShortcut(string shortcutPath, string executable)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(shortcutPath));
            Type shellType = Type.GetTypeFromProgID("WScript.Shell");
            object shell = Activator.CreateInstance(shellType);
            object shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { shortcutPath });
            Type shortcutType = shortcut.GetType();
            shortcutType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, new object[] { executable });
            shortcutType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, new object[] { Path.GetDirectoryName(executable) });
            shortcutType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, new object[] { executable + ",0" });
            shortcutType.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
        }

        private static void RegisterUninstaller(string target)
        {
            using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
            using (RegistryKey key = machine.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\PerRoute"))
            {
                key.SetValue("DisplayName", "PerRoute");
                key.SetValue("DisplayVersion", Version);
                key.SetValue("Publisher", "Ali Mirzad");
                key.SetValue("InstallLocation", target);
                key.SetValue("DisplayIcon", Path.Combine(target, "PerRoute.exe"));
                key.SetValue("UninstallString", "\"" + Path.Combine(target, "Uninstall.exe") + "\"");
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }
        }

        /// <summary>
        /// The version installed in a folder: its version file, or for installations from before 3.5 the version
        /// Windows lists for PerRoute. Empty when nothing is installed there.
        /// </summary>
        private static string InstalledVersion(string folder)
        {
            if (!File.Exists(Path.Combine(folder, "PerRoute.exe"))) return "";
            try
            {
                string file = Path.Combine(folder, "perroute-version.txt");
                if (File.Exists(file)) return File.ReadAllText(file).Trim();
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (RegistryKey key = machine.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\PerRoute"))
                {
                    object listed = key == null ? null : key.GetValue("DisplayVersion");
                    object location = key == null ? null : key.GetValue("InstallLocation");
                    if (listed != null && location != null && String.Equals(location.ToString().TrimEnd('\\'), folder.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                        return listed.ToString().Trim();
                }
            }
            catch { }
            return "";
        }

        private static string DefaultInstallDirectory()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "PerRoute");
        }

        private static string NormalizeInstallDirectory(string value)
        {
            if (String.IsNullOrWhiteSpace(value)) throw new ArgumentException("Choose an installation folder.");
            string path = Path.GetFullPath(Environment.ExpandEnvironmentVariables(value.Trim().Trim('"')))
                .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (String.IsNullOrEmpty(path) || String.Equals(path, Path.GetPathRoot(path), StringComparison.OrdinalIgnoreCase))
                throw new ArgumentException("A drive root cannot be used as the installation folder.");
            return path;
        }

        private static void ValidateInstallDirectory(string path)
        {
            if (!Directory.Exists(path)) return;
            string[] entries = Directory.GetFileSystemEntries(path);
            if (entries.Length == 0 || File.Exists(Path.Combine(path, "PerRoute.exe"))) return;
            throw new ArgumentException("The selected folder is not empty. Choose an empty folder or an existing PerRoute installation.");
        }
    }

    internal sealed class InstallProgress
    {
        internal readonly int Percent;
        internal readonly string Status;
        internal InstallProgress(int percent, string status) { Percent = percent; Status = status; }
    }

    internal sealed class InstallationResult
    {
        internal readonly string Executable;
        internal readonly bool RestartRequired;
        internal InstallationResult(string executable, bool restartRequired)
        {
            Executable = executable;
            RestartRequired = restartRequired;
        }
    }
}
