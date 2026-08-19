Add-Type -AssemblyName System.Drawing

$code = @"
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;

public class IconGenerator
{
    public static void Generate(string outPath)
    {
        int size = 1024;
        using (Bitmap bmp = new Bitmap(size, size))
        using (Graphics g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            g.PixelOffsetMode = PixelOffsetMode.Half;

            Rectangle rect = new Rectangle(0, 0, size, size);

            // 圆角裁剪
            int corner = 230;
            GraphicsPath clipPath = new GraphicsPath();
            clipPath.AddArc(0, 0, corner * 2, corner * 2, 180, 90);
            clipPath.AddArc(size - corner * 2, 0, corner * 2, corner * 2, 270, 90);
            clipPath.AddArc(size - corner * 2, size - corner * 2, corner * 2, corner * 2, 0, 90);
            clipPath.AddArc(0, size - corner * 2, corner * 2, corner * 2, 90, 90);
            clipPath.CloseFigure();
            g.SetClip(clipPath);

            // 径向渐变背景
            using (GraphicsPath bgPath = new GraphicsPath())
            {
                bgPath.AddRectangle(rect);
                using (PathGradientBrush bgBrush = new PathGradientBrush(bgPath))
                {
                    bgBrush.CenterColor = Color.FromArgb(232, 247, 255);
                    bgBrush.SurroundColors = new Color[] { Color.FromArgb(22, 119, 255) };
                    bgBrush.CenterPoint = new PointF(512, 360);
                    bgBrush.FocusScales = new PointF(0.35f, 0.25f);
                    g.FillRectangle(bgBrush, rect);
                }
            }

            // 顶部高光
            using (SolidBrush highlight = new SolidBrush(Color.FromArgb(45, 255, 255, 255)))
            {
                g.FillEllipse(highlight, 152, 100, 720, 320);
            }

            // 智能道路箭头
            using (Pen roadPen = new Pen(Color.FromArgb(19, 194, 194), 42))
            {
                roadPen.StartCap = LineCap.Round;
                roadPen.EndCap = LineCap.Round;
                using (GraphicsPath roadPath = new GraphicsPath())
                {
                    roadPath.AddBezier(220, 600, 380, 700, 644, 700, 820, 580);
                    g.DrawPath(roadPen, roadPath);
                }
            }
            using (SolidBrush arrowBrush = new SolidBrush(Color.FromArgb(19, 194, 194)))
            {
                g.FillPolygon(arrowBrush, new PointF[] {
                    new PointF(762, 526),
                    new PointF(848, 580),
                    new PointF(762, 634),
                    new PointF(786, 580)
                });
            }

            // 货车投影
            using (SolidBrush shadow = new SolidBrush(Color.FromArgb(55, 0, 0, 0)))
            {
                g.FillEllipse(shadow, 312, 525, 400, 60);
            }

            int truckX = 512;
            int truckY = 420;
            Color white = Color.White;
            Color lightBlue = Color.FromArgb(186, 231, 255);
            Color dark = Color.FromArgb(29, 33, 41);
            Color brandBlue = Color.FromArgb(22, 119, 255);
            Color yellow = Color.FromArgb(255, 214, 102);

            // 车厢
            Rectangle bodyRect = new Rectangle(truckX - 235, truckY - 110, 310, 190);
            int bodyRadius = 28;
            using (GraphicsPath bodyPath = new GraphicsPath())
            {
                bodyPath.AddArc(bodyRect.X, bodyRect.Y, bodyRadius * 2, bodyRadius * 2, 180, 90);
                bodyPath.AddArc(bodyRect.Right - bodyRadius * 2, bodyRect.Y, bodyRadius * 2, bodyRadius * 2, 270, 90);
                bodyPath.AddArc(bodyRect.Right - bodyRadius * 2, bodyRect.Bottom - bodyRadius * 2, bodyRadius * 2, bodyRadius * 2, 0, 90);
                bodyPath.AddArc(bodyRect.X, bodyRect.Bottom - bodyRadius * 2, bodyRadius * 2, bodyRadius * 2, 90, 90);
                bodyPath.CloseFigure();
                g.FillPath(Brushes.White, bodyPath);
            }

            // 驾驶室
            using (GraphicsPath cabPath = new GraphicsPath())
            {
                int cabX = truckX + 85;
                int cabY = truckY - 110;
                cabPath.AddLine(cabX, cabY, truckX + 170, cabY);
                cabPath.AddBezier(truckX + 170, cabY, truckX + 210, cabY, truckX + 210, cabY + 40, truckX + 210, truckY + 40);
                cabPath.AddLine(truckX + 210, truckY + 40, cabX, truckY + 40);
                cabPath.CloseFigure();
                g.FillPath(Brushes.White, cabPath);
            }

            // 窗户
            using (GraphicsPath winPath = new GraphicsPath())
            {
                winPath.AddLine(truckX + 100, truckY - 95, truckX + 170, truckY - 95);
                winPath.AddBezier(truckX + 170, truckY - 95, truckX + 190, truckY - 95, truckX + 190, truckY - 75, truckX + 190, truckY - 35);
                winPath.AddLine(truckX + 190, truckY - 35, truckX + 100, truckY - 35);
                winPath.CloseFigure();
                using (SolidBrush winBrush = new SolidBrush(lightBlue))
                {
                    g.FillPath(winBrush, winPath);
                }
            }

            // 门把手
            using (SolidBrush handleBrush = new SolidBrush(Color.FromArgb(230, 247, 255)))
            {
                g.FillRectangle(handleBrush, truckX + 105, truckY - 25, 30, 6);
            }

            // 底盘
            using (SolidBrush chassisBrush = new SolidBrush(brandBlue))
            {
                g.FillRectangle(chassisBrush, truckX - 200, truckY + 65, 380, 18);
            }

            // 车轮
            using (SolidBrush wheelBrush = new SolidBrush(dark))
            {
                g.FillEllipse(wheelBrush, truckX - 155 - 38, truckY + 95 - 38, 76, 76);
                g.FillEllipse(wheelBrush, truckX + 145 - 38, truckY + 95 - 38, 76, 76);
            }
            g.FillEllipse(Brushes.White, truckX - 155 - 18, truckY + 95 - 18, 36, 36);
            g.FillEllipse(Brushes.White, truckX + 145 - 18, truckY + 95 - 18, 36, 36);

            // 车灯
            using (SolidBrush lightBrush = new SolidBrush(yellow))
            {
                g.FillRectangle(lightBrush, truckX + 198, truckY, 10, 22);
            }

            // 文字
            using (FontFamily fontFamily = new FontFamily("Microsoft YaHei"))
            using (Font nameFont = new Font(fontFamily, 130, FontStyle.Bold, GraphicsUnit.Pixel))
            using (Font subFont = new Font(fontFamily, 34, FontStyle.Regular, GraphicsUnit.Pixel))
            {
                string name = "\u8FD0\u667A\u901A";
                SizeF nameSize = g.MeasureString(name, nameFont);
                float nameX = (size - nameSize.Width) / 2;
                g.DrawString(name, nameFont, Brushes.White, nameX, 720);

                string sub = "\u667A\u6167\u914D\u9001";
                using (SolidBrush subBrush = new SolidBrush(Color.FromArgb(217, 255, 255, 255)))
                {
                    SizeF subSize = g.MeasureString(sub, subFont);
                    float subX = (size - subSize.Width) / 2;
                    g.DrawString(sub, subFont, subBrush, subX, 855);
                }
            }

            bmp.Save(outPath, ImageFormat.Png);
        }
    }
}
"@

Add-Type -TypeDefinition $code -ReferencedAssemblies System.Drawing
[IconGenerator]::Generate('e:\我的工作项目\erp-wms-tms\yunzhitong-app-icon\assets\yunzhitong-icon.png')
Write-Host 'PNG generated successfully'
