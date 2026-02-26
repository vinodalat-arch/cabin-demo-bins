#!/usr/bin/env python3
"""Generate Detection & Alert Matrix PDF for In-Cabin AI Perception."""

from fpdf import FPDF

class AlertMatrixPDF(FPDF):
    def header(self):
        self.set_font("Helvetica", "B", 10)
        self.set_text_color(100, 100, 100)
        self.cell(0, 6, "In-Cabin AI Perception  |  Detection & Alert Matrix", align="R")
        self.ln(8)

    def footer(self):
        self.set_y(-12)
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(150, 150, 150)
        self.cell(0, 10, f"KPIT Technologies  |  Page {self.page_no()}/{{nb}}", align="C")

    def section_title(self, title):
        self.set_font("Helvetica", "B", 13)
        self.set_text_color(30, 30, 60)
        self.cell(0, 10, title)
        self.ln(8)
        # underline
        self.set_draw_color(91, 141, 239)  # accent blue
        self.set_line_width(0.6)
        self.line(self.l_margin, self.get_y(), self.w - self.r_margin, self.get_y())
        self.ln(4)

    def sub_title(self, title):
        self.set_font("Helvetica", "B", 11)
        self.set_text_color(50, 50, 80)
        self.cell(0, 8, title)
        self.ln(7)

    def table_header(self, col_widths, headers):
        self.set_font("Helvetica", "B", 7.5)
        self.set_fill_color(30, 30, 60)
        self.set_text_color(255, 255, 255)
        for i, h in enumerate(headers):
            self.cell(col_widths[i], 7, h, border=1, fill=True, align="C")
        self.ln()
        self.set_text_color(30, 30, 30)

    def table_row(self, col_widths, cells, aligns=None, bold_first=True, fill=False):
        self.set_font("Helvetica", "", 6.8)
        if fill:
            self.set_fill_color(240, 242, 248)
        else:
            self.set_fill_color(255, 255, 255)

        # Calculate max height needed
        max_lines = 1
        for i, cell in enumerate(cells):
            lines = self.multi_cell(col_widths[i], 4, cell, split_only=True)
            max_lines = max(max_lines, len(lines))
        row_h = max(7, max_lines * 4)

        x_start = self.get_x()
        y_start = self.get_y()

        for i, cell in enumerate(cells):
            x = x_start + sum(col_widths[:i])
            self.set_xy(x, y_start)
            align = (aligns[i] if aligns else ("L" if i == 0 else "C")) if not aligns else aligns[i]
            if bold_first and i == 0:
                self.set_font("Helvetica", "B", 6.8)
            else:
                self.set_font("Helvetica", "", 6.8)
            # Draw cell background and border
            self.rect(x, y_start, col_widths[i], row_h, "DF" if fill else "D")
            self.set_xy(x + 0.5, y_start + 0.5)
            self.multi_cell(col_widths[i] - 1, 4, cell, align=align)

        self.set_xy(x_start, y_start + row_h)


def main():
    pdf = AlertMatrixPDF(orientation="L", format="A4")
    pdf.alias_nb_pages()
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_page()

    # Title
    pdf.set_font("Helvetica", "B", 20)
    pdf.set_text_color(30, 30, 60)
    pdf.cell(0, 12, "Detection & Alert Matrix", align="C")
    pdf.ln(10)
    pdf.set_font("Helvetica", "", 10)
    pdf.set_text_color(100, 100, 100)
    pdf.cell(0, 6, "In-Cabin AI Perception  |  SA8155P / SA8295P  |  Android Automotive 14", align="C")
    pdf.ln(12)

    # ─── Table 1: In-Cabin Detections ───
    pdf.section_title("1. In-Cabin Detections (Smart Cabin)")

    cols = [38, 72, 16, 18, 52, 16, 65]
    pdf.table_header(cols, [
        "Detection", "What It Detects", "Risk Wt", "Severity", "Voice Alert (EN)", "Max Level", "Vehicle Actions at Max Level"
    ])

    rows = [
        ["Phone use", "Phone detected near driver's hand area", "3", "CRITICAL",
         "Phone detected, please put it down", "L5",
         "Chime, Dashboard, TTS, Notif, Beep, Alarm, Cabin Lights, Seat Haptic/Thermal, Steering Heat, Window, ADAS"],
        ["Eyes closed", "Driver's eyes detected as closed for multiple frames", "3", "CRITICAL",
         "Eyes closed, please stay alert", "L5", "Same as phone"],
        ["Hands off wheel", "Driver's hands not visible on steering wheel", "3", "CRITICAL",
         "Hands off wheel, please grip the steering", "L5", "Same as phone"],
        ["Distracted", "Driver looking away from the road (head turned or tilted)", "2", "WARNING",
         "Distracted, please watch the road", "L5", "Same as phone"],
        ["Yawning", "Driver's mouth open wide (yawning pattern)", "2", "WARNING",
         "Yawning detected, consider a break", "L4",
         "Chime, Dashboard, TTS, Notif, Beep, Cabin Lights, Seat Haptic/Thermal, Steering Heat"],
        ["Eating/drinking", "Food or drink item detected near driver", "1", "WARNING",
         "Eating while driving, please focus", "L3",
         "Chime, Dashboard, TTS, Notif, Beep, Cabin Lights, Seat Haptic"],
        ["Dangerous posture", "Driver leaning excessively, head drooping, or face not visible", "2", "WARNING",
         "Dangerous posture detected", "L3", "Same as eating"],
        ["Child slouching", "Child passenger leaning or slouching significantly", "1", "WARNING",
         "Child is slouching, please check", "L3", "Same as eating"],
    ]

    for i, row in enumerate(rows):
        pdf.table_row(cols, row, aligns=["L","L","C","C","L","C","L"], fill=(i % 2 == 0))

    pdf.ln(6)

    # ─── Table 2: Risk Scoring ───
    pdf.section_title("2. Risk Scoring")
    cols2 = [40, 60, 177]
    pdf.table_header(cols2, ["Risk Level", "Condition", "Score Formula"])
    risk_rows = [
        ["HIGH", "3 or more points", "Phone (3), Eyes closed (3), Hands off (3), Distracted (2), Yawning (2), Posture (2), Eating (1), Slouch (1)"],
        ["MEDIUM", "1 or 2 points", "Sum of active detection weights from above"],
        ["LOW", "0 points", "No active detections"],
    ]
    for i, row in enumerate(risk_rows):
        pdf.table_row(cols2, row, aligns=["C","C","L"], fill=(i % 2 == 0))

    pdf.ln(6)

    # ─── Table 3: 5-Level Escalation Timeline ───
    pdf.section_title("3. Five-Level Escalation Timeline")
    cols3 = [35, 28, 80, 134]
    pdf.table_header(cols3, ["Level", "After", "App Alerts", "Vehicle Hardware Actions"])
    esc_rows = [
        ["L1 Nudge", "0s", "Chime, Dashboard warning", "None (skipped when parked)"],
        ["L2 Warning", "5s", "+ Voice alert, Notification", "None"],
        ["L3 Urgent", "10s", "+ Warning beep", "Cabin lights flash, Seat vibration"],
        ["L4 Intervention", "20s", "All app alerts active", "+ Seat cooling, Steering wheel heat"],
        ["L5 Emergency", "30s+", "+ Continuous alarm", "+ Window opens slightly, ADAS engaged"],
    ]
    for i, row in enumerate(esc_rows):
        pdf.table_row(cols3, row, aligns=["L","C","L","L"], fill=(i % 2 == 0))

    pdf.ln(6)

    # ─── Table 4: Audio Escalation Ladder ───
    pdf.section_title("4. Audio Escalation Ladder")
    cols4 = [45, 232]
    pdf.table_header(cols4, ["Duration", "Audio Response"])
    audio_rows = [
        ["0s (onset)", "Voice announces the danger(s); critical dangers spoken first"],
        ["5s", "Voice: \"Still distracted, 5 seconds\""],
        ["10s", "Warning beep (1 second) followed by voice: \"Warning. Distracted 10 seconds\""],
        ["20s, 30s, ...", "Beep repeats every 10 seconds with updated duration"],
        ["All-clear", "All pending alerts cleared, voice says \"All clear\""],
    ]
    for i, row in enumerate(audio_rows):
        pdf.table_row(cols4, row, aligns=["L","L"], fill=(i % 2 == 0))

    pdf.ln(6)

    # ─── Table 5: Rear-View Detections ───
    pdf.section_title("5. Rear-View Detections (Reverse Gear)")
    cols5 = [45, 65, 22, 60, 85]
    pdf.table_header(cols5, ["Detection", "What It Detects", "Severity", "Voice Alert (EN)", "Notes"])
    rear_rows = [
        ["Person behind vehicle", "Person detected behind the vehicle while reversing", "CRITICAL",
         "Person behind vehicle", "Warning beep before voice; immediate collision risk"],
        ["Animal behind vehicle", "Cat or dog detected behind the vehicle while reversing", "WARNING",
         "Animal behind vehicle", "Voice only; advisory alert"],
    ]
    for i, row in enumerate(rear_rows):
        pdf.table_row(cols5, row, aligns=["L","L","C","L","L"], fill=(i % 2 == 0))

    pdf.ln(6)

    # ─── Table 6: Key Timing Constants ───
    pdf.section_title("6. Key Timing Constants")
    cols6 = [90, 40]
    pdf.table_header(cols6, ["Parameter", "Value"])
    timing_rows = [
        ["Repeat alert cooldown", "10s between same alert"],
        ["Stale message discard", "4s (ignored if too old)"],
        ["Warning beep duration", "1 second"],
        ["Alert queue size", "Up to 3 pending alerts"],
        ["Detection smoothing", "3 frames, majority vote"],
        ["Eyes-open fast reset", "2 consecutive open-eye frames"],
        ["Eye baseline learning", "First 10 frames"],
        ["Head angle baseline learning", "First 10 frames"],
        ["Head angle smoothing", "3-frame average"],
        ["Face recognition frequency", "Every 5th frame"],
        ["Pipeline watchdog", "Restarts after 30s stall"],
        ["UI stall indicator", "Shows after 15s with no update"],
    ]
    for i, row in enumerate(timing_rows):
        pdf.table_row(cols6, row, aligns=["L","C"], fill=(i % 2 == 0), bold_first=False)

    # Output
    out_path = "/Users/vinodalat/Projects/in_cabin_poc-sa8155/Detection_Alert_Matrix.pdf"
    pdf.output(out_path)
    print(f"PDF generated: {out_path}")

if __name__ == "__main__":
    main()
