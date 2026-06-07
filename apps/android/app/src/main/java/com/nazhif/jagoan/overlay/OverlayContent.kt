package com.nazhif.jagoan.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

/** A category option shown as a chip in the overlay. */
data class OverlayCategory(val key: String, val label: String)

/** The transaction payload the overlay renders, sourced from the server's webhook response. */
data class OverlayTransaction(
    val transactionId: String,
    val amount: Double,
    val categories: List<OverlayCategory>,
)

/** UI states driven by the controller as the confirm request progresses. */
sealed interface OverlayUiState {
    data object Editing : OverlayUiState
    data object Saving : OverlayUiState
    data object Success : OverlayUiState
    data class Error(val message: String) : OverlayUiState
}

// ---- Glass palette (intentionally fixed dark, independent of system theme) ----
private val CardBg = Color(0xF01A1B24)       // mostly-opaque dark (frosted, no see-through)
private val HairLine = Color(0x1FFFFFFF)
private val Accent = Color(0xFF6366F1)       // indigo
private val AccentSoft = Color(0xFFA5B4FC)
private val Muted = Color(0xFF8C8C9A)
private val FieldBg = Color(0x0DFFFFFF)
private val ChipBg = Color(0x0AFFFFFF)
private val ChipText = Color(0xFFCFCFDA)

/** Format an amount as Indonesian Rupiah, mirroring the server's Intl.NumberFormat("id-ID"). */
fun formatIdr(amount: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID"))
    format.maximumFractionDigits = 0
    return format.format(amount)
}

/** Strip the leading "Rp" the formatter adds, so we can style it separately. */
private fun amountDigits(amount: Double): String =
    formatIdr(amount).replace("Rp", "").trim()

/**
 * The floating "glass" overlay: a compact translucent card with the amount + tag on top,
 * a purpose field, a horizontally-scrolling category row, and compact actions. The amount
 * is display-only (by design).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayContent(
    transaction: OverlayTransaction,
    uiState: OverlayUiState,
    onSave: (name: String, categoryKey: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var purpose by remember { mutableStateOf("") }
    var selectedKey by remember {
        mutableStateOf(transaction.categories.firstOrNull()?.key)
    }

    val isBusy = uiState is OverlayUiState.Saving || uiState is OverlayUiState.Success
    val canSave = purpose.isNotBlank() && !isBusy

    // Floating: full-width window, but margins + bottom gap keep the card off the edges.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 8.dp, bottom = 26.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(CardBg)
                .border(1.dp, HairLine, RoundedCornerShape(26.dp))
                .padding(18.dp)
        ) {
            if (uiState is OverlayUiState.Success) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✅", fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Tersimpan!",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                return@Box
            }

            Column {
                // Top: amount (left) + OUTGOING tag (right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "Rp ",
                                color = AccentSoft,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                amountDigits(transaction.amount),
                                color = Color.White,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "🎯 Pengeluaran baru",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0x2E818CF8))
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "OUTGOING",
                            color = AccentSoft,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it },
                    placeholder = { Text("Untuk keperluan apa?") },
                    singleLine = true,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSave) onSave(purpose.trim(), selectedKey) },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = HairLine,
                        focusedContainerColor = FieldBg,
                        unfocusedContainerColor = FieldBg,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Accent,
                        focusedPlaceholderColor = Muted,
                        unfocusedPlaceholderColor = Muted,
                    ),
                )

                if (transaction.categories.isNotEmpty()) {
                    Spacer(Modifier.height(13.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        transaction.categories.forEach { category ->
                            GlassChip(
                                label = category.label,
                                selected = category.key == selectedKey,
                                enabled = !isBusy,
                                onClick = { selectedKey = category.key },
                            )
                        }
                    }
                }

                if (uiState is OverlayUiState.Error) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        uiState.message,
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, enabled = !isBusy) {
                        Text("Batal", color = Muted, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(purpose.trim(), selectedKey) },
                        enabled = canSave,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0x33FFFFFF),
                            disabledContentColor = Color(0x80FFFFFF),
                        ),
                    ) {
                        if (uiState is OverlayUiState.Saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Text("Simpan", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Accent else ChipBg)
            .border(
                1.dp,
                if (selected) Color.Transparent else HairLine,
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else ChipText,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
