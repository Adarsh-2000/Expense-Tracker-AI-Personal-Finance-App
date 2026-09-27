package com.ledgernoir.tracker.ui.auth

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.BorderGray
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Onboarding screen shown after Google sign-in when the profile has no PAN.
 * Collects DOB (via Material3 DatePicker, stored as "DD-MM-YYYY") and PAN
 * (validated client-side), then PUTs to /profile. "Skip for now" leaves the
 * profile empty (portfolio simply won't populate until PAN is set).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    repository: ExpenseRepository,
    onDone: () -> Unit,
) {
    val vm: OnboardingViewModel = viewModel(
        factory = ledgerViewModelFactory { OnboardingViewModel(it.useCases.putProfile) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val pan = state.pan
    val dob = state.dob
    val showDatePicker = state.showDatePicker
    val saving = state.saving
    val errorMsg = state.errorMsg
    val panValid = state.panValid
    val panError = state.panError

    val isCover = com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current ==
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover
    val haptic = LocalHapticFeedback.current

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { vm.dismissDatePicker() },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val localDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        vm.onDobSelected(localDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")))
                    }
                    vm.dismissDatePicker()
                }) {
                    Text("OK", color = AccentTeal, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissDatePicker() }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        ResponsiveContainer {
            Text(
                text = "Complete your profile",
                style = MaterialTheme.typography.headlineMedium.copy(
                    brush = Brush.linearGradient(listOf(AccentTeal, AccentGreen)),
                ),
                fontFamily = DisplayFamily,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "We use your PAN to securely read your investment statements. " +
                    "It stays on your own server.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(if (isCover) 14.dp else 28.dp))

            // DOB field — clickable, opens the date picker.
            Text(
                text = "Date of birth",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark, RoundedCornerShape(14.dp))
                    .border(1.dp, BorderGray, RoundedCornerShape(14.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.showDatePicker()
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = "Pick date",
                        tint = AccentTeal,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = dob ?: "DD-MM-YYYY",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (dob != null) TextPrimary else TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // PAN field.
            Text(
                text = "PAN",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pan,
                onValueChange = { vm.onPanChange(it) },
                placeholder = { Text("ABCDE1234F", color = TextMuted) },
                singleLine = true,
                isError = panError,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentTeal,
                    unfocusedBorderColor = BorderGray,
                    errorBorderColor = DebitRose,
                    cursorColor = AccentTeal,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    errorContainerColor = SurfaceDark,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (panError) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Enter a valid PAN (e.g. ABCDE1234F).",
                    style = MaterialTheme.typography.labelSmall,
                    color = DebitRose,
                )
            }

            if (errorMsg != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = errorMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = DebitRose,
                )
            }

            Spacer(Modifier.height(if (isCover) 14.dp else 28.dp))

            // Continue button.
            val continueEnabled = panValid && !saving
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (continueEnabled) AccentTeal else AccentTeal.copy(alpha = 0.3f),
                        RoundedCornerShape(16.dp),
                    )
                    .let { m ->
                        if (continueEnabled) m.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.save(onDone)
                        } else m
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        color = CanvasBlack,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = "Continue",
                        color = CanvasBlack,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Skip for now.
            TextButton(
                onClick = {
                    if (!saving) onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Skip for now",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your PAN and date of birth never leave your own server.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}
