using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Resources;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("PerRoute Setup")]
[assembly: AssemblyDescription("Installer for PerRoute and the PerRoute browser extension")]
[assembly: AssemblyCompany("PerRoute")]
[assembly: AssemblyProduct("PerRoute")]
[assembly: AssemblyVersion(ReleaseInfo.Version + ".0")]
[assembly: AssemblyFileVersion(ReleaseInfo.Version + ".0")]

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
                MessageBox.Show("Administrator approval is required to install PerRoute.",
                    "Administrator access required", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            string[] args = Environment.GetCommandLineArgs();
            string updateDirectory = null;
            for (int i = 1; i < args.Length; i++)
                if (String.Equals(args[i], "/update", StringComparison.OrdinalIgnoreCase))
                    updateDirectory = i + 1 < args.Length ? args[i + 1] : "";
            Application.Run(new InstallerForm(updateDirectory));
        }

        private static bool IsAdministrator()
        {
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
                return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
    }

    // ------------------------------------------------------------------ look

    internal static class Palette
    {
        internal static readonly Color Background = Color.FromArgb(21, 23, 22);
        internal static readonly Color Rail = Color.FromArgb(25, 27, 26);
        internal static readonly Color Surface = Color.FromArgb(29, 32, 31);
        internal static readonly Color SurfaceHigh = Color.FromArgb(38, 41, 40);
        internal static readonly Color Line = Color.FromArgb(43, 47, 46);
        internal static readonly Color Text = Color.FromArgb(237, 237, 231);
        internal static readonly Color Muted = Color.FromArgb(156, 160, 164);
        internal static readonly Color Lime = Color.FromArgb(216, 242, 74);
        internal static readonly Color OnLime = Color.FromArgb(20, 22, 20);
        internal static readonly Color Ok = Color.FromArgb(83, 196, 122);
        internal static readonly Color Danger = Color.FromArgb(240, 112, 112);
        internal static readonly Color Warn = Color.FromArgb(240, 180, 64);

        private static string family;
        internal static Font Font(float size, FontStyle style = FontStyle.Regular)
        {
            if (family == null)
            {
                family = "Segoe UI";
                using (InstalledFontCollection fonts = new InstalledFontCollection())
                    foreach (FontFamily f in fonts.Families)
                        if (f.Name == "Segoe UI Variable Text") { family = f.Name; break; }
            }
            return new Font(family, size, style, GraphicsUnit.Point);
        }

        internal static GraphicsPath Rounded(RectangleF r, float radius)
        {
            GraphicsPath p = new GraphicsPath();
            float d = radius * 2;
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }
    }

    /// <summary>Base for the drawn controls: double-buffered, transparent to the parent's color.</summary>
    internal class Drawn : Control
    {
        internal Drawn()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint |
                ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            ForeColor = Palette.Text;
        }

        protected static void Smooth(Graphics g)
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        }
    }

    internal sealed class FlatButton : Drawn
    {
        internal bool Primary;
        private bool hover;
        private bool down;

        internal FlatButton(string text, bool primary)
        {
            Text = text;
            Primary = primary;
            Font = Palette.Font(9.75F, FontStyle.Bold);
            Cursor = Cursors.Hand;
            TabStop = true;
            Size = new Size(120, 40);
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnEnabledChanged(EventArgs e) { Cursor = Enabled ? Cursors.Hand : Cursors.Default; Invalidate(); base.OnEnabledChanged(e); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Enter || e.KeyCode == Keys.Space) { OnClick(EventArgs.Empty); e.Handled = true; }
            base.OnKeyDown(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Smooth(g);
            RectangleF r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            Color fill = Primary ? Palette.Lime : Palette.SurfaceHigh;
            if (Enabled && down) fill = ControlPaint.Dark(fill, 0.05f);
            else if (Enabled && hover) fill = Primary ? ControlPaint.Light(fill, 0.25f) : Color.FromArgb(48, 52, 51);
            if (!Enabled) fill = Primary ? Color.FromArgb(82, 92, 40) : Palette.Surface;
            using (GraphicsPath p = Palette.Rounded(r, 9))
            {
                using (SolidBrush b = new SolidBrush(fill)) g.FillPath(b, p);
                if (!Primary) using (Pen pen = new Pen(Palette.Line)) g.DrawPath(pen, p);
                if (Focused && ShowFocusCues) using (Pen pen = new Pen(Color.FromArgb(160, Palette.Lime), 2)) g.DrawPath(pen, p);
            }
            Color text = Primary ? Palette.OnLime : Palette.Text;
            if (!Enabled) text = Primary ? Color.FromArgb(150, Palette.OnLime) : Palette.Muted;
            TextRenderer.DrawText(g, Text, Font, ClientRectangle, text, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
        }
    }

    /// <summary>A component that can be chosen: a card with a title, a description and a check mark.</summary>
    internal sealed class ComponentCard : Drawn
    {
        internal string Title;
        internal string Description;
        internal string Note;
        internal string Glyph;
        private bool isChecked = true;
        private bool hover;
        internal event EventHandler CheckedChanged;

        internal bool Checked
        {
            get { return isChecked; }
            set { if (isChecked == value) return; isChecked = value; Invalidate(); if (CheckedChanged != null) CheckedChanged(this, EventArgs.Empty); }
        }

        internal ComponentCard()
        {
            Cursor = Cursors.Hand;
            TabStop = true;
            Height = 112;
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnClick(EventArgs e) { Focus(); Checked = !Checked; base.OnClick(e); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Space || e.KeyCode == Keys.Enter) { Checked = !Checked; e.Handled = true; }
            base.OnKeyDown(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Smooth(g);
            RectangleF r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            using (GraphicsPath p = Palette.Rounded(r, 12))
            {
                using (SolidBrush b = new SolidBrush(isChecked ? Color.FromArgb(35, 41, 20) : (hover ? Palette.SurfaceHigh : Palette.Surface))) g.FillPath(b, p);
                Color border = isChecked ? Palette.Lime : (Focused ? Color.FromArgb(120, Palette.Lime) : Palette.Line);
                using (Pen pen = new Pen(border, isChecked ? 1.6f : 1f)) g.DrawPath(pen, p);
            }
            // Glyph tile
            RectangleF tile = new RectangleF(18, 20, 44, 44);
            using (GraphicsPath p = Palette.Rounded(tile, 10))
            using (SolidBrush b = new SolidBrush(isChecked ? Palette.Lime : Palette.SurfaceHigh))
                g.FillPath(b, p);
            using (Font gf = new Font("Segoe MDL2 Assets", 16F))
                TextRenderer.DrawText(g, Glyph, gf, Rectangle.Round(tile), isChecked ? Palette.OnLime : Palette.Text,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            // Text
            int left = 78;
            int right = Width - 64;
            using (Font tf = Palette.Font(11.25F, FontStyle.Bold))
                TextRenderer.DrawText(g, Title, tf, new Rectangle(left, 16, right - left, 24), Palette.Text, TextFormatFlags.Left | TextFormatFlags.EndEllipsis);
            using (Font df = Palette.Font(9F))
            {
                TextRenderer.DrawText(g, Description, df, new Rectangle(left, 42, right - left, 40), Palette.Muted, TextFormatFlags.Left | TextFormatFlags.WordBreak);
                if (!String.IsNullOrEmpty(Note))
                    TextRenderer.DrawText(g, Note, df, new Rectangle(left, 84, right - left, 20), isChecked ? Palette.Lime : Palette.Muted, TextFormatFlags.Left | TextFormatFlags.EndEllipsis);
            }
            // Check mark
            RectangleF c = new RectangleF(Width - 46, 22, 24, 24);
            if (isChecked)
            {
                using (SolidBrush b = new SolidBrush(Palette.Lime)) g.FillEllipse(b, c);
                using (Pen pen = new Pen(Palette.OnLime, 2.2f) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round })
                    g.DrawLines(pen, new[] { new PointF(c.X + 7, c.Y + 12.5f), new PointF(c.X + 10.5f, c.Y + 16), new PointF(c.X + 17.5f, c.Y + 8.5f) });
            }
            else using (Pen pen = new Pen(Palette.Muted, 1.6f)) g.DrawEllipse(pen, c);
        }
    }

    /// <summary>A small check box drawn in the installer's style.</summary>
    internal sealed class CheckRow : Drawn
    {
        private bool isChecked;
        internal bool Checked { get { return isChecked; } set { isChecked = value; Invalidate(); } }

        internal CheckRow(string text, bool value)
        {
            Text = text;
            isChecked = value;
            Font = Palette.Font(9.75F);
            Cursor = Cursors.Hand;
            TabStop = true;
            Height = 28;
        }

        protected override void OnClick(EventArgs e) { Focus(); Checked = !Checked; base.OnClick(e); }
        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Space) { Checked = !Checked; e.Handled = true; }
            base.OnKeyDown(e);
        }
        protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Smooth(g);
            RectangleF box = new RectangleF(1, (Height - 18) / 2f, 18, 18);
            using (GraphicsPath p = Palette.Rounded(box, 5))
            {
                if (isChecked) using (SolidBrush b = new SolidBrush(Enabled ? Palette.Lime : Palette.Muted)) g.FillPath(b, p);
                else using (Pen pen = new Pen(Focused ? Palette.Lime : Palette.Muted, 1.4f)) g.DrawPath(pen, p);
            }
            if (isChecked)
                using (Pen pen = new Pen(Palette.OnLime, 2f) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                    g.DrawLines(pen, new[] { new PointF(box.X + 4.5f, box.Y + 9.5f), new PointF(box.X + 7.5f, box.Y + 12.5f), new PointF(box.X + 13.5f, box.Y + 5.5f) });
            TextRenderer.DrawText(g, Text, Font, new Rectangle(28, 0, Width - 28, Height), Enabled ? Palette.Text : Palette.Muted,
                TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        }
    }

    /// <summary>One numbered instruction: a lime number and wrapped text.</summary>
    internal sealed class NumberedStep : Drawn
    {
        private readonly int number;

        internal NumberedStep(int number, string text)
        {
            this.number = number;
            Text = text;
            Font = Palette.Font(9.75F);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Smooth(g);
            RectangleF dot = new RectangleF(0, 2, 22, 22);
            using (SolidBrush b = new SolidBrush(Palette.Lime)) g.FillEllipse(b, dot);
            using (Font f = Palette.Font(9F, FontStyle.Bold))
                TextRenderer.DrawText(g, number.ToString(), f, Rectangle.Round(dot), Palette.OnLime, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            TextRenderer.DrawText(g, Text, Font, new Rectangle(34, 3, Width - 34, Height - 3), Palette.Text, TextFormatFlags.Left | TextFormatFlags.WordBreak);
        }
    }

    internal sealed class ThinProgress : Drawn
    {
        private int value;
        internal int Value { get { return value; } set { this.value = Math.Max(0, Math.Min(100, value)); Invalidate(); } }
        internal ThinProgress() { Height = 6; }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Smooth(g);
            RectangleF r = new RectangleF(0, 0, Width - 1, Height - 1);
            using (GraphicsPath p = Palette.Rounded(r, (Height - 1) / 2f))
            using (SolidBrush b = new SolidBrush(Palette.SurfaceHigh))
                g.FillPath(b, p);
            float w = (Width - 1) * value / 100f;
            if (w < Height) return;
            using (GraphicsPath p = Palette.Rounded(new RectangleF(0, 0, w, Height - 1), (Height - 1) / 2f))
            using (SolidBrush b = new SolidBrush(Palette.Lime))
                g.FillPath(b, p);
        }
    }

    /// <summary>The steps of the installation, each pending, running, done or failed.</summary>
    internal sealed class StepList : Drawn
    {
        internal enum State { Pending, Running, Done, Failed }
        internal readonly List<string> Steps = new List<string>();
        internal readonly List<State> States = new List<State>();
        private int spin;
        private readonly Timer timer = new Timer { Interval = 90 };

        internal StepList()
        {
            timer.Tick += delegate { spin = (spin + 1) % 12; if (States.Contains(State.Running)) Invalidate(); };
            timer.Start();
        }

        internal void Set(int index, State state)
        {
            if (index < 0 || index >= States.Count) return;
            States[index] = state;
            Invalidate();
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) timer.Dispose();
            base.Dispose(disposing);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Smooth(g);
            using (Font f = Palette.Font(10F))
            using (Font fb = Palette.Font(10F, FontStyle.Bold))
                for (int i = 0; i < Steps.Count; i++)
                {
                    int y = i * 36;
                    RectangleF dot = new RectangleF(2, y + 6, 20, 20);
                    State s = States[i];
                    if (s == State.Done)
                    {
                        using (SolidBrush b = new SolidBrush(Palette.Ok)) g.FillEllipse(b, dot);
                        using (Pen pen = new Pen(Palette.Background, 2f) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                            g.DrawLines(pen, new[] { new PointF(dot.X + 5.5f, dot.Y + 10.5f), new PointF(dot.X + 8.5f, dot.Y + 13.5f), new PointF(dot.X + 14.5f, dot.Y + 7) });
                    }
                    else if (s == State.Failed)
                    {
                        using (SolidBrush b = new SolidBrush(Palette.Danger)) g.FillEllipse(b, dot);
                        using (Pen pen = new Pen(Palette.Background, 2f))
                        {
                            g.DrawLine(pen, dot.X + 6.5f, dot.Y + 6.5f, dot.X + 13.5f, dot.Y + 13.5f);
                            g.DrawLine(pen, dot.X + 13.5f, dot.Y + 6.5f, dot.X + 6.5f, dot.Y + 13.5f);
                        }
                    }
                    else if (s == State.Running)
                    {
                        using (Pen track = new Pen(Palette.SurfaceHigh, 2.5f)) g.DrawEllipse(track, dot);
                        using (Pen arc = new Pen(Palette.Lime, 2.5f) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                            g.DrawArc(arc, dot, spin * 30, 100);
                    }
                    else using (Pen pen = new Pen(Palette.Line, 2f)) g.DrawEllipse(pen, dot);
                    TextRenderer.DrawText(g, Steps[i], s == State.Running ? fb : f, new Rectangle(34, y, Width - 34, 32),
                        s == State.Pending ? Palette.Muted : Palette.Text, TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
                }
        }
    }

    // ------------------------------------------------------------------ installer window

    internal sealed class InstallerForm : Form
    {
        internal const string Version = ReleaseInfo.Version;
        private const string ResourceName = "PerRoute.Payload";
        private const string BrowserResourceName = "PerRoute.BrowserPayload";
        private const string ExtensionIdResourceName = "PerRoute.ExtensionId";
        private const string ProxiFyreResourceName = "PerRoute.ProxiFyreSetup";
        private const string VisualCppResourceName = "PerRoute.VisualCppRuntime";
        private const string WindowsPacketFilterResourceName = "PerRoute.WindowsPacketFilter";
        private const string ProxiFyreFileName = "ProxiFyre-2.6.2-win-x64-setup.exe";
        private const string VisualCppFileName = "VC_redist.x64.exe";
        private const string WindowsPacketFilterFileName = "Windows.Packet.Filter.3.6.2.1.x64.msi";
        private const string ProxiFyreSha256 = "952CA0F40571194E7DFCD922A92BC3BEF1D0759A90C602E8632A9AF958C13B73";
        private const string VisualCppSha256 = "CC0FF0EB1DC3F5188AE6300FAEF32BF5BEEBA4BDD6E8E445A9184072096B713B";
        private const string WindowsPacketFilterSha256 = "9C388C0B7F189F7FA98720BAE2CAECF7D64F30910838B80B438ECF8956B8502C";
        internal const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\PerRoute";
        internal const string HostName = "com.perroute.browser";
        internal const string BrowserFolderName = "PerRoute Browser";
        /// <summary>Registry keys under which Chromium browsers look for native messaging hosts.</summary>
        internal static readonly string[] NativeMessagingKeys = {
            @"Software\Google\Chrome\NativeMessagingHosts",
            @"Software\Chromium\NativeMessagingHosts",
            @"Software\Microsoft\Edge\NativeMessagingHosts",
            @"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts",
            @"Software\Vivaldi\NativeMessagingHosts",
        };

        private readonly bool updateMode;
        private readonly Panel rail = new Panel();
        private readonly Panel body = new Panel();
        private readonly Panel footer = new Panel();
        private readonly Label railSteps = new Label();
        private readonly FlatButton backButton = new FlatButton("Back", false);
        private readonly FlatButton nextButton = new FlatButton("Next", true);
        private readonly FlatButton cancelButton = new FlatButton("Cancel", false);
        private readonly ComponentCard appCard = new ComponentCard();
        private readonly ComponentCard browserCard = new ComponentCard();
        private readonly TextBox locationBox = new TextBox();
        private readonly CheckRow desktopShortcut = new CheckRow("Create a desktop shortcut", true);
        private readonly CheckRow launchAfterInstall = new CheckRow("Open PerRoute when setup finishes", true);
        private readonly StepList steps = new StepList();
        private readonly ThinProgress progressBar = new ThinProgress();
        private readonly Label statusLabel = new Label();
        private int page;
        private InstallationResult result;

        internal InstallerForm(string updateDirectory = null)
        {
            updateMode = updateDirectory != null;
            Text = "PerRoute Setup";
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96F, 96F);
            ClientSize = new Size(820, 540);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Palette.Background;
            ForeColor = Palette.Text;
            Font = Palette.Font(9.75F);
            DoubleBuffered = true;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            BuildRail();
            body.SetBounds(232, 0, 588, 468);
            body.BackColor = Palette.Background;
            Controls.Add(body);
            footer.SetBounds(232, 468, 588, 72);
            footer.BackColor = Palette.Background;
            footer.Paint += delegate(object s, PaintEventArgs e) { using (Pen p = new Pen(Palette.Line)) e.Graphics.DrawLine(p, 0, 0, footer.Width, 0); };
            Controls.Add(footer);
            cancelButton.SetBounds(32, 16, 104, 40);
            backButton.SetBounds(332, 16, 104, 40);
            nextButton.SetBounds(448, 16, 108, 40);
            footer.Controls.Add(cancelButton);
            footer.Controls.Add(backButton);
            footer.Controls.Add(nextButton);
            cancelButton.Click += delegate { Close(); };
            backButton.Click += delegate { if (page > 0 && page < 2) ShowPage(page - 1); };
            nextButton.Click += NextClicked;

            appCard.Title = "PerRoute app";
            appCard.Description = "Choose which Windows apps use the tunnel. Installs the per-app network engine, so no app needs its own proxy settings.";
            appCard.Note = "For all apps on this computer";
            appCard.Glyph = "";
            browserCard.Title = "Browser extension";
            browserCard.Description = "PerRoute inside Chrome, Edge, Brave and Vivaldi: choose site by site what uses the tunnel. Works without the app.";
            browserCard.Note = "You add it to your browser in one step at the end";
            browserCard.Glyph = "";
            appCard.CheckedChanged += delegate { UpdateButtons(); };
            browserCard.CheckedChanged += delegate { UpdateButtons(); };

            locationBox.Text = DefaultInstallDirectory();
            if (updateMode)
            {
                if (updateDirectory.Length > 0) locationBox.Text = updateDirectory;
                desktopShortcut.Checked = File.Exists(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory), "PerRoute.lnk"));
                appCard.Checked = true;
                browserCard.Checked = InstalledBrowserDirectory() != null;
                Shown += delegate { ShowPage(2); };
            }
            else Shown += delegate { ShowPage(0); };
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            try
            {
                int dark = 1;
                if (DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int)) != 0) DwmSetWindowAttribute(Handle, 19, ref dark, sizeof(int));
                int caption = ColorTranslator.ToWin32(Palette.Background);
                DwmSetWindowAttribute(Handle, 35, ref caption, sizeof(int));
            }
            catch { }
        }

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

        private void BuildRail()
        {
            rail.SetBounds(0, 0, 232, 540);
            rail.BackColor = Palette.Rail;
            rail.Paint += delegate(object s, PaintEventArgs e) { using (Pen p = new Pen(Palette.Line)) e.Graphics.DrawLine(p, rail.Width - 1, 0, rail.Width - 1, rail.Height); };
            Controls.Add(rail);
            PictureBox logo = new PictureBox { Bounds = new Rectangle(28, 32, 44, 44), SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.Transparent };
            try { logo.Image = new Icon(Icon.ExtractAssociatedIcon(Application.ExecutablePath), new Size(256, 256)).ToBitmap(); } catch { }
            rail.Controls.Add(logo);
            rail.Controls.Add(new Label { Text = "PerRoute", Font = Palette.Font(15F, FontStyle.Bold), ForeColor = Palette.Text, AutoSize = true, Location = new Point(26, 88), BackColor = Color.Transparent });
            rail.Controls.Add(new Label { Text = "Version " + Version, Font = Palette.Font(9F), ForeColor = Palette.Muted, AutoSize = true, Location = new Point(28, 118), BackColor = Color.Transparent });
            railSteps.SetBounds(28, 170, 190, 200);
            railSteps.BackColor = Color.Transparent;
            railSteps.Paint += PaintRailSteps;
            rail.Controls.Add(railSteps);
            rail.Controls.Add(new Label { Text = "PerRoute", Font = Palette.Font(8.25F, FontStyle.Bold), ForeColor = Color.FromArgb(150, Palette.Lime), AutoSize = true, Location = new Point(28, 500), BackColor = Color.Transparent });
        }

        private void PaintRailSteps(object sender, PaintEventArgs e)
        {
            string[] names = { "Choose components", "Options", "Install", "Finish" };
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            for (int i = 0; i < names.Length; i++)
            {
                int y = i * 40;
                bool active = i == page;
                bool done = i < page;
                RectangleF dot = new RectangleF(0, y + 8, 14, 14);
                using (SolidBrush b = new SolidBrush(active ? Palette.Lime : done ? Palette.Ok : Palette.SurfaceHigh)) g.FillEllipse(b, dot);
                if (i < names.Length - 1)
                    using (Pen p = new Pen(done ? Palette.Ok : Palette.Line, 2)) g.DrawLine(p, 7, y + 24, 7, y + 46);
                using (Font f = Palette.Font(9.75F, active ? FontStyle.Bold : FontStyle.Regular))
                    TextRenderer.DrawText(g, names[i], f, new Rectangle(24, y, 170, 30), active ? Palette.Text : Palette.Muted, TextFormatFlags.Left | TextFormatFlags.VerticalCenter);
            }
        }

        private static Label Heading(string text, int y)
        {
            return new Label { Text = text, Font = Palette.Font(18F, FontStyle.Bold), ForeColor = Palette.Text, AutoSize = false, Bounds = new Rectangle(32, y, 524, 40), BackColor = Color.Transparent };
        }

        private static Label Paragraph(string text, int y, int height = 40, Color? color = null)
        {
            return new Label { Text = text, Font = Palette.Font(9.75F), ForeColor = color ?? Palette.Muted, AutoSize = false, Bounds = new Rectangle(32, y, 524, height), BackColor = Color.Transparent };
        }

        private void ShowPage(int index)
        {
            page = index;
            body.SuspendLayout();
            body.Controls.Clear();
            switch (index)
            {
                case 0: BuildComponentsPage(); break;
                case 1: BuildOptionsPage(); break;
                case 2: BuildProgressPage(); break;
                default: BuildFinishPage(); break;
            }
            body.ResumeLayout();
            railSteps.Invalidate();
            UpdateButtons();
            if (index == 2) StartInstall();
        }

        private void UpdateButtons()
        {
            backButton.Visible = page == 1;
            cancelButton.Visible = page < 2;
            nextButton.Visible = page != 2;
            nextButton.Text = page == 0 ? "Next" : page == 1 ? "Install" : "Finish";
            nextButton.Enabled = page != 0 || appCard.Checked || browserCard.Checked;
            nextButton.Invalidate();
        }

        private void BuildComponentsPage()
        {
            body.Controls.Add(Heading(updateMode ? "Update PerRoute" : "What would you like to install?", 34));
            body.Controls.Add(Paragraph("Choose one or both. Each works on its own and you can add the other later by running setup again.", 78));
            appCard.SetBounds(32, 132, 524, 112);
            browserCard.SetBounds(32, 258, 524, 112);
            body.Controls.Add(appCard);
            body.Controls.Add(browserCard);
            body.Controls.Add(Paragraph("Both are installed for all users of this computer. Servers and settings stay separate for each Windows user.", 392, 40));
        }

        private void BuildOptionsPage()
        {
            body.Controls.Add(Heading("Options", 34));
            body.Controls.Add(Paragraph("PerRoute is installed in this folder. The browser runtime goes into a \"" + BrowserFolderName + "\" folder next to it.", 78));
            body.Controls.Add(new Label { Text = "INSTALL LOCATION", Font = Palette.Font(8.25F, FontStyle.Bold), ForeColor = Palette.Muted, Bounds = new Rectangle(32, 134, 300, 18), BackColor = Color.Transparent });
            Panel field = new Panel { Bounds = new Rectangle(32, 156, 404, 40), BackColor = Palette.Surface };
            field.Paint += delegate(object s, PaintEventArgs e) { using (Pen p = new Pen(Palette.Line)) e.Graphics.DrawRectangle(p, 0, 0, field.Width - 1, field.Height - 1); };
            locationBox.BorderStyle = BorderStyle.None;
            locationBox.BackColor = Palette.Surface;
            locationBox.ForeColor = Palette.Text;
            locationBox.Font = Palette.Font(10F);
            locationBox.SetBounds(12, 11, 380, 22);
            field.Controls.Add(locationBox);
            body.Controls.Add(field);
            FlatButton browse = new FlatButton("Browse…", false);
            browse.SetBounds(448, 156, 108, 40);
            browse.Click += BrowseLocation;
            body.Controls.Add(browse);
            int y = 226;
            if (appCard.Checked)
            {
                body.Controls.Add(new Label { Text = "PERROUTE APP", Font = Palette.Font(8.25F, FontStyle.Bold), ForeColor = Palette.Muted, Bounds = new Rectangle(32, y, 300, 18), BackColor = Color.Transparent });
                desktopShortcut.SetBounds(32, y + 24, 400, 28);
                launchAfterInstall.SetBounds(32, y + 56, 400, 28);
                body.Controls.Add(desktopShortcut);
                body.Controls.Add(launchAfterInstall);
                body.Controls.Add(Paragraph("Includes ProxiFyre, Windows Packet Filter and the Visual C++ runtime for per-app routing.", y + 92, 40));
                y += 146;
            }
            if (browserCard.Checked)
            {
                body.Controls.Add(new Label { Text = "BROWSER EXTENSION", Font = Palette.Font(8.25F, FontStyle.Bold), ForeColor = Palette.Muted, Bounds = new Rectangle(32, y, 300, 18), BackColor = Color.Transparent });
                body.Controls.Add(Paragraph("Registers the runtime with Chrome, Edge, Brave, Vivaldi and Chromium. Servers you add in the extension are stored encrypted for your Windows user only.", y + 24, 44));
            }
        }

        private void BuildProgressPage()
        {
            body.Controls.Add(Heading(updateMode ? "Updating PerRoute" : "Installing", 34));
            body.Controls.Add(Paragraph("This takes a minute. Keep this window open.", 78));
            steps.Steps.Clear();
            steps.States.Clear();
            steps.Steps.Add("Preparing files");
            if (appCard.Checked) { steps.Steps.Add("Installing the per-app engine"); steps.Steps.Add("Installing PerRoute"); }
            if (browserCard.Checked) { steps.Steps.Add("Installing the browser runtime"); steps.Steps.Add("Connecting your browsers"); }
            steps.Steps.Add("Finishing up");
            foreach (string s in steps.Steps) steps.States.Add(StepList.State.Pending);
            steps.SetBounds(32, 130, 524, steps.Steps.Count * 36 + 4);
            body.Controls.Add(steps);
            progressBar.SetBounds(32, 392, 524, 6);
            progressBar.Value = 2;
            body.Controls.Add(progressBar);
            statusLabel.SetBounds(32, 408, 524, 22);
            statusLabel.ForeColor = Palette.Muted;
            statusLabel.BackColor = Color.Transparent;
            statusLabel.Font = Palette.Font(9F);
            statusLabel.AutoEllipsis = true;
            statusLabel.Text = "Starting…";
            body.Controls.Add(statusLabel);
        }

        private void BuildFinishPage()
        {
            bool app = result != null && result.Executable != null;
            bool browser = result != null && result.BrowserDirectory != null;
            body.Controls.Add(Heading(updateMode ? "PerRoute is up to date" : "PerRoute is ready", 34));
            int y = 82;
            if (app)
            {
                string text = result.RestartRequired
                    ? "The app is installed. Restart Windows before using Smart per-app mode."
                    : "The app is installed" + (launchAfterInstall.Checked ? " and opens when you click Finish." : ". Find it in the Start menu.");
                body.Controls.Add(Paragraph(text, y, 40, Palette.Text));
                y += 46;
            }
            if (!browser) return;
            body.Controls.Add(new Label { Text = "ADD THE EXTENSION TO YOUR BROWSER", Font = Palette.Font(8.25F, FontStyle.Bold), ForeColor = Palette.Lime, Bounds = new Rectangle(32, y, 400, 18), BackColor = Color.Transparent });
            y += 26;
            string extensionFolder = Path.Combine(result.BrowserDirectory, "extension");
            string[] guide = {
                "Open your browser's extensions page: chrome://extensions, edge://extensions or brave://extensions.",
                "Turn on Developer mode (top right; in Edge, on the left).",
                "Click \"Load unpacked\", choose the folder below, then pin PerRoute to the toolbar.",
            };
            for (int i = 0; i < guide.Length; i++)
            {
                NumberedStep s = new NumberedStep(i + 1, guide[i]);
                s.SetBounds(32, y, 524, 42);
                body.Controls.Add(s);
                y += 42;
            }
            Panel pathBox = new Panel { Bounds = new Rectangle(32, y + 4, 524, 40), BackColor = Palette.Surface };
            pathBox.Paint += delegate(object s, PaintEventArgs e) { using (Pen p = new Pen(Palette.Line)) e.Graphics.DrawRectangle(p, 0, 0, pathBox.Width - 1, pathBox.Height - 1); };
            pathBox.Controls.Add(new Label { Text = extensionFolder, Font = Palette.Font(9.5F), ForeColor = Palette.Text, AutoEllipsis = true, Bounds = new Rectangle(12, 10, 500, 22), BackColor = Color.Transparent });
            body.Controls.Add(pathBox);
            y += 56;
            FlatButton copy = new FlatButton("Copy folder path", false);
            copy.SetBounds(32, y, 160, 38);
            copy.Click += delegate { try { Clipboard.SetText(extensionFolder); copy.Text = "Copied"; copy.Invalidate(); } catch { } };
            body.Controls.Add(copy);
            FlatButton open = new FlatButton("Show folder", false);
            open.SetBounds(202, y, 130, 38);
            open.Click += delegate { OpenUnelevated(extensionFolder); };
            body.Controls.Add(open);
            int x = 342;
            foreach (KeyValuePair<string, string> b in InstalledBrowsers())
            {
                if (x > 470) break;
                FlatButton launch = new FlatButton("Open " + b.Key, false);
                launch.SetBounds(x, y, 104, 38);
                string exe = b.Value;
                launch.Click += delegate { OpenUnelevated(exe); };
                body.Controls.Add(launch);
                x += 112;
            }
            y += 50;
            body.Controls.Add(Paragraph("To remove it later, use \"Remove\" on the extensions page, and uninstall PerRoute in Windows Settings.", y, 36));
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

        private void NextClicked(object sender, EventArgs e)
        {
            if (page == 0) { ShowPage(1); return; }
            if (page == 1)
            {
                try
                {
                    string dir = NormalizeInstallDirectory(locationBox.Text);
                    if (appCard.Checked) ValidateInstallDirectory(dir);
                    locationBox.Text = dir;
                }
                catch (Exception error)
                {
                    MessageBox.Show(this, error.Message, "Invalid installation location", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                ShowPage(2);
                return;
            }
            if (result != null && result.Executable != null && launchAfterInstall.Checked && !result.RestartRequired)
                OpenUnelevated(result.Executable);
            Close();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (page == 2 && result == null && e.CloseReason == CloseReason.UserClosing) e.Cancel = true;
            base.OnFormClosing(e);
        }

        private async void StartInstall()
        {
            UseWaitCursor = true;
            bool app = appCard.Checked;
            bool browser = browserCard.Checked;
            bool shortcut = desktopShortcut.Checked;
            string directory = NormalizeInstallDirectory(locationBox.Text);
            Progress<InstallProgress> progress = new Progress<InstallProgress>(delegate(InstallProgress update) {
                progressBar.Value = update.Percent;
                if (update.Status != null) statusLabel.Text = update.Status;
                if (update.Step >= 0)
                {
                    for (int i = 0; i < update.Step; i++) steps.Set(i, StepList.State.Done);
                    steps.Set(update.Step, StepList.State.Running);
                }
            });
            try
            {
                result = await Task.Run(delegate { return Installer.Run(progress, app, browser, shortcut, directory); });
                for (int i = 0; i < steps.Steps.Count; i++) steps.Set(i, StepList.State.Done);
                progressBar.Value = 100;
                UseWaitCursor = false;
                if (result.Executable != null) RefreshShellIcons(result.Executable);
                // An automatic update reopens PerRoute; the extension in the browser needs no step.
                if (updateMode && !result.RestartRequired)
                {
                    if (result.Executable != null) OpenUnelevated(result.Executable);
                    Close();
                    return;
                }
                ShowPage(3);
            }
            catch (Exception error)
            {
                UseWaitCursor = false;
                int running = steps.States.IndexOf(StepList.State.Running);
                steps.Set(running < 0 ? 0 : running, StepList.State.Failed);
                statusLabel.ForeColor = Palette.Danger;
                statusLabel.Text = "Setup did not finish. Nothing you had before was removed.";
                MessageBox.Show(this, error.Message, "PerRoute could not be installed", MessageBoxButtons.OK, MessageBoxIcon.Error);
                result = new InstallationResult(null, false, null);
                page = 1;
                ShowPage(1);
            }
        }

        /// <summary>Starts a program as the signed-in user, not elevated like this installer.</summary>
        private static void OpenUnelevated(string path)
        {
            try { Process.Start("explorer.exe", "\"" + path + "\""); } catch { }
        }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        private static extern void SHChangeNotify(int eventId, uint flags, string item1, IntPtr item2);

        private static void RefreshShellIcons(string executable)
        {
            try
            {
                SHChangeNotify(0x00002000, 0x0005, executable, IntPtr.Zero);
                SHChangeNotify(0x08000000, 0x0000, null, IntPtr.Zero);
                string refresher = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "ie4uinit.exe");
                if (File.Exists(refresher))
                {
                    ProcessStartInfo start = new ProcessStartInfo(refresher, "-show");
                    start.UseShellExecute = false;
                    start.CreateNoWindow = true;
                    using (Process process = Process.Start(start)) process.WaitForExit(10000);
                }
            }
            catch (Exception) { }
        }

        /// <summary>Browsers found on this computer: display name and executable.</summary>
        internal static List<KeyValuePair<string, string>> InstalledBrowsers()
        {
            List<KeyValuePair<string, string>> found = new List<KeyValuePair<string, string>>();
            string[][] known = {
                new[] { "Chrome", "chrome.exe" },
                new[] { "Edge", "msedge.exe" },
                new[] { "Brave", "brave.exe" },
                new[] { "Vivaldi", "vivaldi.exe" },
            };
            foreach (string[] b in known)
            {
                string path = AppPath(Registry.LocalMachine, b[1]) ?? AppPath(Registry.CurrentUser, b[1]);
                if (path != null) found.Add(new KeyValuePair<string, string>(b[0], path));
            }
            return found;
        }

        private static string AppPath(RegistryKey root, string exe)
        {
            try
            {
                using (RegistryKey key = root.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\App Paths\" + exe))
                {
                    string value = key == null ? null : key.GetValue(null) as string;
                    if (value == null) return null;
                    value = value.Trim().Trim('"');
                    return File.Exists(value) ? value : null;
                }
            }
            catch { return null; }
        }

        internal static string InstalledBrowserDirectory()
        {
            try
            {
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (RegistryKey key = machine.OpenSubKey(UninstallKey))
                {
                    string dir = key == null ? null : key.GetValue("BrowserLocation") as string;
                    return dir != null && Directory.Exists(dir) ? dir : null;
                }
            }
            catch { return null; }
        }

        internal static string DefaultInstallDirectory()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "PerRoute");
        }

        internal static string NormalizeInstallDirectory(string value)
        {
            if (String.IsNullOrWhiteSpace(value)) throw new ArgumentException("Choose an installation folder.");
            string path = Path.GetFullPath(Environment.ExpandEnvironmentVariables(value.Trim().Trim('"')))
                .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (String.IsNullOrEmpty(path) || String.Equals(path, Path.GetPathRoot(path), StringComparison.OrdinalIgnoreCase))
                throw new ArgumentException("A drive root cannot be used as the installation folder.");
            return path;
        }

        internal static void ValidateInstallDirectory(string path)
        {
            if (!Directory.Exists(path)) return;
            string[] entries = Directory.GetFileSystemEntries(path);
            if (entries.Length == 0 || File.Exists(Path.Combine(path, "PerRoute.exe"))) return;
            throw new ArgumentException("The selected folder is not empty. Choose an empty folder or an existing PerRoute installation.");
        }

        // ------------------------------------------------------------------ installation work

        internal static class Installer
        {
            internal static InstallationResult Run(IProgress<InstallProgress> progress, bool app, bool browser, bool desktopShortcut, string installDirectory)
            {
                string target = NormalizeInstallDirectory(installDirectory);
                string browserTarget = Path.Combine(Path.GetDirectoryName(target), BrowserFolderName);
                string stage = Path.Combine(Path.GetTempPath(), "PerRoute-install-" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(stage);
                int step = 0;
                try
                {
                    progress.Report(new InstallProgress(4, "Checking the installer files…", step));
                    string appExtracted = null;
                    string dependencyStage = Path.Combine(stage, "dependencies");
                    if (app)
                    {
                        Directory.CreateDirectory(dependencyStage);
                        PrepareDependencyBundle(dependencyStage);
                        string applicationStage = Path.Combine(stage, "application");
                        Directory.CreateDirectory(applicationStage);
                        ExtractZip(ResourceName, applicationStage, progress, 5, 45);
                        string[] extractedDirectories = Directory.GetDirectories(applicationStage);
                        if (extractedDirectories.Length != 1) throw new InvalidDataException("The installer payload is incomplete.");
                        appExtracted = extractedDirectories[0];
                    }
                    string browserExtracted = null;
                    if (browser)
                    {
                        browserExtracted = Path.Combine(stage, "browser");
                        Directory.CreateDirectory(browserExtracted);
                        ExtractZip(BrowserResourceName, browserExtracted, progress, app ? 45 : 5, app ? 55 : 50);
                    }

                    string executable = null;
                    bool restartRequired = false;
                    if (app)
                    {
                        progress.Report(new InstallProgress(58, "Installing the per-app network engine and driver…", ++step));
                        restartRequired = InstallPerAppEngine(dependencyStage);
                        VerifyPerAppEngine(restartRequired);
                        RemoveDependencyShortcuts();
                        progress.Report(new InstallProgress(72, "Installing PerRoute for all users…", ++step));
                        executable = InstallApplication(appExtracted, target, desktopShortcut);
                    }
                    if (browser)
                    {
                        progress.Report(new InstallProgress(80, "Installing the browser runtime…", ++step));
                        InstallBrowserRuntime(browserExtracted, browserTarget);
                        progress.Report(new InstallProgress(90, "Registering the runtime with your browsers…", ++step));
                        RegisterNativeMessaging(browserTarget);
                    }
                    progress.Report(new InstallProgress(96, "Finishing up…", ++step));
                    string uninstaller = app ? Path.Combine(target, "Uninstall.exe") : Path.Combine(browserTarget, "Uninstall.exe");
                    RegisterUninstaller(uninstaller, app ? target : ExistingAppLocation(), browser ? browserTarget : InstalledBrowserDirectory());
                    progress.Report(new InstallProgress(100, "Done.", -1));
                    return new InstallationResult(executable, restartRequired, browser ? browserTarget : null);
                }
                catch (IOException error)
                {
                    throw new IOException("PerRoute files could not be updated. Close PerRoute and your browsers, then try again.\r\n\r\n" + error.Message, error);
                }
                finally
                {
                    try { if (Directory.Exists(stage)) Directory.Delete(stage, true); } catch { }
                }
            }

            private static string ExistingAppLocation()
            {
                try
                {
                    using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                    using (RegistryKey key = machine.OpenSubKey(UninstallKey))
                    {
                        string dir = key == null ? null : key.GetValue("InstallLocation") as string;
                        return dir != null && File.Exists(Path.Combine(dir, "PerRoute.exe")) ? dir : null;
                    }
                }
                catch { return null; }
            }

            private static string InstallApplication(string extracted, string target, bool desktopShortcut)
            {
                ValidateInstallDirectory(target);
                Directory.CreateDirectory(Path.GetDirectoryName(target));
                string backup = target + ".previous";
                string rollback = target + ".rollback";
                string previousVersion = InstalledVersion(target);
                if (Directory.Exists(backup)) Directory.Delete(backup, true);
                bool backedUp = false;
                try
                {
                    if (Directory.Exists(target))
                    {
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
                ResetStateKeyPermissions();
                string executable = Path.Combine(target, "PerRoute.exe");
                CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Programs", "PerRoute.lnk"), executable);
                string recovery = Path.Combine(target, "PerRouteRecovery.exe");
                if (File.Exists(recovery)) CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Programs", "PerRoute Recovery.lnk"), recovery);
                string desktopLink = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory), "PerRoute.lnk");
                if (desktopShortcut) CreateShortcut(desktopLink, executable);
                else if (File.Exists(desktopLink)) File.Delete(desktopLink);
                return executable;
            }

            /// <summary>
            /// The browser runtime: the helper, the pinned Xray and the unpacked extension, in a folder
            /// only administrators can change. Running helpers (one per open browser profile) are stopped
            /// first; the extension reconnects to the new one by itself.
            /// </summary>
            private static void InstallBrowserRuntime(string extracted, string target)
            {
                if (!File.Exists(Path.Combine(extracted, "perroute-browser-host.exe")) || !Directory.Exists(Path.Combine(extracted, "extension")))
                    throw new InvalidDataException("The browser runtime package is incomplete.");
                if (Directory.Exists(target) && Directory.GetFileSystemEntries(target).Length > 0 && !File.Exists(Path.Combine(target, "perroute-browser-host.exe")))
                    throw new ArgumentException("The folder \"" + target + "\" already exists and is not a PerRoute browser runtime.");
                StopBrowserRuntime(target);
                string backup = target + ".previous";
                if (Directory.Exists(backup)) Directory.Delete(backup, true);
                bool backedUp = false;
                try
                {
                    if (Directory.Exists(target))
                    {
                        Directory.Move(target, backup);
                        backedUp = true;
                    }
                    Directory.Move(extracted, target);
                }
                catch
                {
                    if (!Directory.Exists(target) && Directory.Exists(backup)) Directory.Move(backup, target);
                    throw;
                }
                if (backedUp) try { Directory.Delete(backup, true); } catch { }
                File.WriteAllText(Path.Combine(target, "perroute-version.txt"), Version);
            }

            internal static void StopBrowserRuntime(string folder)
            {
                if (String.IsNullOrEmpty(folder)) return;
                string prefix = folder.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                foreach (string name in new[] { "perroute-browser-host", "xray" })
                    foreach (Process process in Process.GetProcessesByName(name))
                    {
                        try
                        {
                            if (!process.MainModule.FileName.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) continue;
                            process.Kill();
                            process.WaitForExit(5000);
                        }
                        catch { }
                    }
            }

            private static string ExtensionId()
            {
                using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream(ExtensionIdResourceName))
                {
                    if (s == null) throw new MissingManifestResourceException("The extension ID is missing from the installer.");
                    using (StreamReader r = new StreamReader(s))
                    {
                        string id = r.ReadToEnd().Trim();
                        if (id.Length != 32) throw new InvalidDataException("The extension ID in the installer is invalid.");
                        foreach (char c in id) if (c < 'a' || c > 'p') throw new InvalidDataException("The extension ID in the installer is invalid.");
                        return id;
                    }
                }
            }

            private static string JsonString(string value)
            {
                StringBuilder b = new StringBuilder("\"");
                foreach (char c in value)
                {
                    if (c == '\\' || c == '"') b.Append('\\').Append(c);
                    else if (c < 0x20) b.AppendFormat("\\u{0:x4}", (int)c);
                    else b.Append(c);
                }
                return b.Append('"').ToString();
            }

            /// <summary>
            /// Native messaging: a host manifest that only the PerRoute extension may use, registered for
            /// every Chromium browser for all users (both registry views, as Chromium checks both).
            /// </summary>
            private static void RegisterNativeMessaging(string folder)
            {
                string host = Path.Combine(folder, "perroute-browser-host.exe");
                string manifestPath = Path.Combine(folder, HostName + ".json");
                string manifest = "{\r\n" +
                    "  \"name\": " + JsonString(HostName) + ",\r\n" +
                    "  \"description\": \"PerRoute browser runtime\",\r\n" +
                    "  \"path\": " + JsonString(host) + ",\r\n" +
                    "  \"type\": \"stdio\",\r\n" +
                    "  \"allowed_origins\": [" + JsonString("chrome-extension://" + ExtensionId() + "/") + "]\r\n" +
                    "}\r\n";
                File.WriteAllText(manifestPath, manifest, new UTF8Encoding(false));
                foreach (RegistryView view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
                    using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                        foreach (string key in NativeMessagingKeys)
                            using (RegistryKey k = machine.CreateSubKey(key + "\\" + HostName))
                                k.SetValue(null, manifestPath);
            }

            private static void ExtractZip(string resource, string destination, IProgress<InstallProgress> progress, int from, int to)
            {
                Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream(resource);
                if (payload == null) throw new MissingManifestResourceException("A part of the installer is missing: " + resource);
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
                        if (String.IsNullOrEmpty(item.Name))
                        {
                            Directory.CreateDirectory(output);
                            continue;
                        }
                        Directory.CreateDirectory(Path.GetDirectoryName(output));
                        using (Stream input = item.Open())
                        using (FileStream file = new FileStream(output, FileMode.Create, FileAccess.Write, FileShare.None)) input.CopyTo(file);
                        extracted += item.Length;
                        int percent = total == 0 ? to : from + (int)((to - from) * extracted / total);
                        progress.Report(new InstallProgress(percent, "Extracting " + item.Name + "…", 0));
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

            private static bool InstallPerAppEngine(string directory)
            {
                string setup = Path.Combine(directory, ProxiFyreFileName);
                ProcessStartInfo start = new ProcessStartInfo(setup, "/install /quiet /norestart")
                {
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

            private static void VerifyPerAppEngine(bool restartPending)
            {
                string executable = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "ProxiFyre", "ProxiFyre.exe");
                if (!File.Exists(executable)) throw new InvalidDataException("The per-app engine did not finish installing.");
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (RegistryKey service = machine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\ProxiFyreService"))
                    if (service == null) throw new InvalidDataException("The ProxiFyre Windows service was not registered.");
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (RegistryKey driver = machine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\ndisrd"))
                    if (driver == null && !restartPending) throw new InvalidDataException("The Windows Packet Filter driver was not installed. Another network filter or security tool may have blocked it. Restart Windows and run the installer again.");
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

            private static void ResetStateKeyPermissions()
            {
                try
                {
                    string key = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PerRoute", "state.bin.key");
                    if (!File.Exists(key)) return;
                    ProcessStartInfo start = new ProcessStartInfo("icacls.exe", "\"" + key + "\" /reset");
                    start.UseShellExecute = false;
                    start.CreateNoWindow = true;
                    using (Process process = Process.Start(start)) process.WaitForExit(10000);
                }
                catch (Exception) { }
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

            /// <summary>One entry in Windows Settings for whatever is installed (app, browser runtime or both).</summary>
            private static void RegisterUninstaller(string uninstaller, string appLocation, string browserLocation)
            {
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (RegistryKey key = machine.CreateSubKey(UninstallKey))
                {
                    key.SetValue("DisplayName", appLocation == null ? "PerRoute browser extension" : "PerRoute");
                    key.SetValue("DisplayVersion", Version);
                    key.SetValue("Publisher", "PerRoute");
                    if (appLocation != null)
                    {
                        key.SetValue("InstallLocation", appLocation);
                        key.SetValue("DisplayIcon", Path.Combine(appLocation, "PerRoute.exe"));
                    }
                    else
                    {
                        key.DeleteValue("InstallLocation", false);
                        key.SetValue("DisplayIcon", uninstaller);
                    }
                    if (browserLocation != null) key.SetValue("BrowserLocation", browserLocation);
                    else key.DeleteValue("BrowserLocation", false);
                    key.SetValue("UninstallString", "\"" + uninstaller + "\"");
                    key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                    key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                }
            }

            private static string InstalledVersion(string folder)
            {
                if (!File.Exists(Path.Combine(folder, "PerRoute.exe"))) return "";
                try
                {
                    string file = Path.Combine(folder, "perroute-version.txt");
                    if (File.Exists(file)) return File.ReadAllText(file).Trim();
                    using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                    using (RegistryKey key = machine.OpenSubKey(UninstallKey))
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
        }
    }

    internal sealed class InstallProgress
    {
        internal readonly int Percent;
        internal readonly string Status;
        /// <summary>Index of the step now running, or -1 to leave the steps as they are.</summary>
        internal readonly int Step;
        internal InstallProgress(int percent, string status, int step) { Percent = percent; Status = status; Step = step; }
    }

    internal sealed class InstallationResult
    {
        internal readonly string Executable;
        internal readonly bool RestartRequired;
        internal readonly string BrowserDirectory;
        internal InstallationResult(string executable, bool restartRequired, string browserDirectory)
        {
            Executable = executable;
            RestartRequired = restartRequired;
            BrowserDirectory = browserDirectory;
        }
    }
}
