package com.dentifay.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.data.Appointment
import com.dentifay.app.data.MockData
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv

/** Zaman çizelgesi kartı (dikey çizgi + nokta + cam kart); mola için ince ayraç. */
@Composable
fun TimelineAppointmentCard(appointment: Appointment, modifier: Modifier = Modifier) {
    if (appointment.isBreak) BreakView(modifier) else AppointmentView(appointment, modifier)
}

@Composable
private fun BreakView(modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TimelineDot(DS.Colors.slate500)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            Text(
                "Öğle arası  ·  1 sa".upperTr(),
                style = sys(11, FontWeight.Bold, tracking = 1.1),
                color = DS.Colors.slate500.copy(alpha = 0.6f),
                maxLines = 1,
            )
            Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        }
    }
}

@Composable
private fun AppointmentView(appointment: Appointment, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TimelineDot(appointment.accentColor)
            Box(Modifier.width(1.dp).weight(1f).background(Color.White.copy(alpha = 0.1f)))
        }

        Column(Modifier.weight(1f)) {
            Text(
                appointment.time, style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.slate500,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    // `.ultraThinMaterial` yerine yarı saydam koyu yüzey.
                    .background(Color.White.copy(alpha = 0.03f))
                    .background(DS.Colors.surface.copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                appointment.patientName, style = sys(18, FontWeight.SemiBold),
                                color = if (appointment.accentColor == DS.Colors.cyan400) DS.Colors.cyan400 else DS.Colors.onSurface,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DfyIcon(appointment.icon, size = 12.dp, tint = DS.Colors.onSurfaceVariant)
                                Text(appointment.treatment, style = sys(14), color = DS.Colors.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            appointment.room,
                            style = sys(11, FontWeight.Bold, tracking = 0.5),
                            color = appointment.accentColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(appointment.accentColor.copy(alpha = 0.1f))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }

                    if (appointment.note.isNotEmpty()) {
                        Row(
                            Modifier
                                .drawBehind { drawRect(Color.White.copy(alpha = 0.05f), size = Size(size.width, 1.dp.toPx())) }
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (appointment.note.contains("allerg") || appointment.note.contains("warning")) {
                                DfyIcon("exclamationmark.triangle.fill", size = 11.dp, tint = DS.Colors.orange400)
                            }
                            Text(appointment.note, style = sys(12), color = DS.Colors.slate400)
                        }
                    }
                }
                // Soldaki vurgu çubuğu.
                Box(Modifier.matchParentSize().padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(appointment.accentColor))
                }
            }
        }
    }
}

@Composable
private fun TimelineDot(color: Color) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).background(DS.Colors.surface, CircleShape))
        Box(Modifier.size(22.dp).border(1.5.dp, color, CircleShape))
        Box(Modifier.size(6.dp).background(color, CircleShape))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TimelineAppointmentCardPreview() {
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TimelineAppointmentCard(MockData.appointments[0])
            TimelineAppointmentCard(MockData.appointments[4])
            TimelineAppointmentCard(MockData.appointments[2])
        }
    }
}
