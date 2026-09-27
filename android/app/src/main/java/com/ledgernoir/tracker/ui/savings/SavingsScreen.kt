package com.ledgernoir.tracker.ui.savings

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.api.FixedDepositDto
import com.ledgernoir.tracker.data.api.FixedDepositPayload
import com.ledgernoir.tracker.data.api.SavingsAccountDto
import com.ledgernoir.tracker.data.api.SavingsResponse
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/** Per-bank accent for the account chip. */
private fun bankColor(bank: String): androidx.compose.ui.graphics.Color = when (bank.uppercase()) {
    "AXIS" -> androidx.compose.ui.graphics.Color(0xFFAE275F)
    "BOB" -> androidx.compose.ui.graphics.Color(0xFFF26522)
    "IDFC" -> androidx.compose.ui.graphics.Color(0xFF9C1D26)
    else -> AccentTeal
}

// ── Public dispatcher — byte-identical signature ───────────────────────────────

@Composable
fun SavingsScreen(repository: ExpenseRepository) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> SavingsCover(repository)
        else -> SavingsMain(repository)
    }
}

// ── Main (phone / large) ───────────────────────────────────────────────────────

@Composable
private fun SavingsMain(repository: ExpenseRepository) {
    val vm: SavingsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            SavingsViewModel(
                it.useCases.getSavings, it.useCases.setBalance,
                it.useCases.addFixedDeposit, it.useCases.deleteFixedDeposit,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val data = state.data
    val loading = state.loading
    var editAccount by remember { mutableStateOf<SavingsAccountDto?>(null) }
    var showAddFd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            Text(
                text = "Savings",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontFamily = DisplayFamily,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Balances & fixed deposits",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(20.dp))

            val d = data
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (d == null) {
                Text("Couldn't load savings.", color = TextSecondary)
            } else {
                // Hero: total liquid (savings + FD).
                val animated by animateFloatAsState(
                    targetValue = d.liquidTotal.toFloat(),
                    animationSpec = tween(800),
                    label = "liquid",
                )
                CredCard {
                    Text(
                        text = "TOTAL LIQUID",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    MoneyText(amount = animated.toDouble(), fontSize = 32.sp,
                        color = AccentGreen, showPaise = false)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Savings ${formatWhole(d.savingsTotal)}  ·  FDs ${formatWhole(d.fdTotal)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                }

                Spacer(Modifier.height(22.dp))
                SavingsSectionLabel("Savings accounts")
                Spacer(Modifier.height(12.dp))
                d.accounts.forEach { acc ->
                    AccountCard(acc = acc, onEdit = { editAccount = acc })
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SavingsSectionLabel("Fixed deposits")
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceMid)
                            .clickable { showAddFd = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, "Add FD", tint = AccentTeal,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add FD", color = AccentTeal,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (d.fixedDeposits.isEmpty()) {
                    CredCard {
                        Text("No fixed deposits yet.", color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap “Add FD” to track one.", color = TextMuted,
                            style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    d.fixedDeposits.forEach { fd ->
                        FixedDepositCard(fd = fd, onDelete = {
                            vm.deleteFixedDeposit(fd.id)
                        })
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // --- Manual balance edit dialog ---
    editAccount?.let { acc ->
        EditBalanceDialog(
            acc = acc,
            onDismiss = { editAccount = null },
            onSave = { value ->
                vm.setBalance(acc.id, value)
                editAccount = null
            },
        )
    }

    // --- Add FD dialog ---
    if (showAddFd) {
        AddFixedDepositDialog(
            onDismiss = { showAddFd = false },
            onSave = { fd ->
                vm.addFixedDeposit(fd)
                showAddFd = false
            },
        )
    }
}

// ── Cover (small square watch-adjacent form factor) ────────────────────────────

@Composable
private fun SavingsCover(repository: ExpenseRepository) {
    val vm: SavingsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            SavingsViewModel(
                it.useCases.getSavings, it.useCases.setBalance,
                it.useCases.addFixedDeposit, it.useCases.deleteFixedDeposit,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val data = state.data
    val loading = state.loading
    var editAccount by remember { mutableStateOf<SavingsAccountDto?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ResponsiveContainer {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (data == null) {
                Text("Couldn't load savings.", color = TextSecondary)
            } else {
                HeroNumber(
                    label = "Total liquid",
                    amount = data.liquidTotal,
                    change = null,
                )
                Spacer(Modifier.height(16.dp))

                SavingsSectionLabel("Savings accounts")
                Spacer(Modifier.height(10.dp))
                data.accounts.forEach { acc ->
                    AccountCard(acc = acc, onEdit = { editAccount = acc })
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(6.dp))
                if (data.fixedDeposits.isNotEmpty()) {
                    SavingsSectionLabel("Fixed deposits")
                    Spacer(Modifier.height(10.dp))
                    CredCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${data.fixedDeposits.size} FD${if (data.fixedDeposits.size == 1) "" else "s"} ›",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            MoneyText(
                                amount = data.fdTotal,
                                fontSize = 18.sp,
                                color = AccentGreen,
                                showPaise = false,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        data.fixedDeposits.forEach { fd ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${fd.bank} FD",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                MoneyText(
                                    amount = fd.principal,
                                    fontSize = 14.sp,
                                    color = TextPrimary,
                                    showPaise = false,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // --- Manual balance edit dialog ---
    editAccount?.let { acc ->
        EditBalanceDialog(
            acc = acc,
            onDismiss = { editAccount = null },
            onSave = { value ->
                vm.setBalance(acc.id, value)
                editAccount = null
            },
        )
    }
}

@Composable
private fun AccountCard(acc: SavingsAccountDto, onEdit: () -> Unit) {
    CredCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(bankColor(acc.bank).copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Savings, acc.bank, tint = bankColor(acc.bank),
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${acc.bank} Savings",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = acc.last4?.let { "••$it" } ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceMid)
                    .clickable { onEdit() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Edit, "Edit balance", tint = TextSecondary,
                    modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        if (acc.balance != null) {
            MoneyText(amount = acc.balance, fontSize = 26.sp, color = TextPrimary)
        } else {
            Text("Not available", color = TextMuted, fontSize = 20.sp)
        }
        Spacer(Modifier.height(6.dp))
        val sourceLabel = when (acc.source) {
            "manual" -> "edited by you"
            "sms" -> "from SMS"
            else -> "no balance on record"
        }
        Text(
            text = (acc.asOf?.let { "as of $it · " } ?: "") + sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun FixedDepositCard(fd: FixedDepositDto, onDelete: () -> Unit) {
    CredCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${fd.bank} FD",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                fd.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
            MoneyText(amount = fd.principal, fontSize = 18.sp, color = AccentGreen)
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            fd.interestRate?.let {
                FdMeta("Rate", "%.2f%% p.a.".format(it))
            }
            fd.maturityDate?.let {
                FdMeta("Matures", it)
            }
            fd.maturityAmount?.let {
                FdMeta("On maturity", formatWhole(it))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Remove",
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            modifier = Modifier.clickable { onDelete() },
        )
    }
}

@Composable
private fun SavingsSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        letterSpacing = 1.4.sp,
    )
}

@Composable
private fun FdMeta(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(value, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
    }
}

@Composable
private fun EditBalanceDialog(
    acc: SavingsAccountDto,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(acc.balance?.let { "%.0f".format(it) } ?: "") }
    val value = text.replace(",", "").toDoubleOrNull()
    AlertDialog(
        containerColor = SurfaceDark,
        onDismissRequest = onDismiss,
        title = { Text("Update ${acc.bank} balance", color = TextPrimary) },
        text = {
            Column {
                Text(
                    "Enter the current balance from your bank app. Your figure becomes the latest.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Balance (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentTeal,
                        focusedLabelColor = AccentTeal,
                        cursorColor = AccentTeal,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null) {
                Text("Save", color = if (value != null) AccentTeal else TextMuted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

@Composable
private fun AddFixedDepositDialog(
    onDismiss: () -> Unit,
    onSave: (FixedDepositPayload) -> Unit,
) {
    var bank by remember { mutableStateOf("") }
    var principal by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var maturity by remember { mutableStateOf("") }
    var maturityAmt by remember { mutableStateOf("") }
    val principalVal = principal.replace(",", "").toDoubleOrNull()
    val valid = bank.isNotBlank() && principalVal != null

    AlertDialog(
        containerColor = SurfaceDark,
        onDismissRequest = onDismiss,
        title = { Text("Add fixed deposit", color = TextPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FdField(bank, { bank = it }, "Bank (e.g. HDFC)", KeyboardType.Text)
                Spacer(Modifier.height(10.dp))
                FdField(principal, { principal = it.filter { c -> c.isDigit() || c == '.' } },
                    "Principal (₹)", KeyboardType.Number)
                Spacer(Modifier.height(10.dp))
                FdField(rate, { rate = it.filter { c -> c.isDigit() || c == '.' } },
                    "Interest rate % p.a. (optional)", KeyboardType.Number)
                Spacer(Modifier.height(10.dp))
                FdField(maturity, { maturity = it }, "Maturity date YYYY-MM-DD (optional)", KeyboardType.Number)
                Spacer(Modifier.height(10.dp))
                FdField(maturityAmt, { maturityAmt = it.filter { c -> c.isDigit() || c == '.' } },
                    "Maturity amount ₹ (optional)", KeyboardType.Number)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(FixedDepositPayload(
                        bank = bank.trim(),
                        principal = principalVal ?: 0.0,
                        interestRate = rate.toDoubleOrNull(),
                        maturityDate = maturity.ifBlank { null },
                        maturityAmount = maturityAmt.toDoubleOrNull(),
                    ))
                },
                enabled = valid,
            ) { Text("Add", color = if (valid) AccentTeal else TextMuted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

@Composable
private fun FdField(value: String, onChange: (String) -> Unit, label: String, kb: KeyboardType) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentTeal,
            focusedLabelColor = AccentTeal,
            cursorColor = AccentTeal,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
        ),
    )
}
