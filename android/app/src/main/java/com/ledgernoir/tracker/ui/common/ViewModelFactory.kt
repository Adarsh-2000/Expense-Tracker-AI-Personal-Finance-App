package com.ledgernoir.tracker.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ledgernoir.tracker.LedgerNoirApp

/**
 * Tiny no-DI ViewModel factory helper. Pulls the [LedgerNoirApp] application
 * instance out of the [APPLICATION_KEY] in the creation extras and hands it to
 * [create], so a ViewModel can be constructed with the app-owned singletons
 * (repository, sync state) without Hilt or any DI framework.
 *
 * Usage:
 * ```
 * val vm: FooViewModel = viewModel(
 *     factory = ledgerViewModelFactory { FooViewModel(it.repository) },
 * )
 * ```
 */
inline fun <reified VM : ViewModel> ledgerViewModelFactory(
    crossinline create: (LedgerNoirApp) -> VM,
) = viewModelFactory {
    initializer { create(this[APPLICATION_KEY] as LedgerNoirApp) }
}
