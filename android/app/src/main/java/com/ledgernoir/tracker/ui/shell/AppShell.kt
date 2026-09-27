package com.ledgernoir.tracker.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ledgernoir.tracker.LedgerNoirApp
import com.ledgernoir.tracker.ui.designsystem.FormFactor
import com.ledgernoir.tracker.ui.designsystem.LocalFormFactor
import com.ledgernoir.tracker.ui.designsystem.ProvideFormFactor
import com.ledgernoir.tracker.ui.dashboard.DashboardScreen
import com.ledgernoir.tracker.ui.transactions.TransactionsScreen
import com.ledgernoir.tracker.ui.transactions.TransactionListScreen
import com.ledgernoir.tracker.ui.transactions.Last3BreakdownScreen
import com.ledgernoir.tracker.ui.insights.AiInsightsScreen
import com.ledgernoir.tracker.ui.insights.InsightsScreen
import com.ledgernoir.tracker.ui.insights.SpendingInsightsScreen
import com.ledgernoir.tracker.ui.insights.SaveMoreScreen
import com.ledgernoir.tracker.ui.portfolio.PortfolioScreen
import com.ledgernoir.tracker.ui.savings.SavingsScreen
import com.ledgernoir.tracker.ui.bills.BillsScreen
import com.ledgernoir.tracker.ui.budget.BudgetScreen
import com.ledgernoir.tracker.ui.networth.NetWorthScreen
import com.ledgernoir.tracker.ui.notifications.NotificationsScreen
import com.ledgernoir.tracker.ui.insights.MoneyRadarScreen
import com.ledgernoir.tracker.ui.rewards.RewardsScreen
import com.ledgernoir.tracker.ui.rewards.RewardDetailScreen
import com.ledgernoir.tracker.ui.auth.SignInScreen
import com.ledgernoir.tracker.ui.auth.OnboardingScreen
import com.ledgernoir.tracker.ui.auth.LockScreen
import com.ledgernoir.tracker.ui.settings.SettingsScreen
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String, val label: String) {
    object SignIn       : Screen("signin",       "Sign In")
    object PostSignIn   : Screen("postsignin",   "")
    object Onboarding   : Screen("onboarding",   "Onboarding")
    object Dashboard    : Screen("dashboard",    "Home")
    object Transactions : Screen("transactions", "Txns")
    object Insights     : Screen("insights",     "Insights")
    object Portfolio    : Screen("portfolio",    "Invest")
    object Savings      : Screen("savings",      "Savings")
    object Bills        : Screen("bills",        "Bills")
    object Budget       : Screen("budget",       "Budget")
    object Settings     : Screen("settings",     "Settings")
    object NetWorth     : Screen("networth",     "Net Worth")
    object Spending     : Screen("spending",     "How I Pay")
    object SaveMore     : Screen("savemore",     "Save More")
    object Notifications : Screen("notifications", "Notifications")
    object MoneyRadar   : Screen("money_radar",  "Money Radar")
    object AiInsights   : Screen("ai_insights",  "AI Insights")
}

// Route templates for the drill-down detail screens (arguments carried in the path).
private const val ROUTE_TXN_RANGE = "txnrange/{start}/{end}/{title}"
private const val ROUTE_TXN_CAT   = "txncat/{start}/{end}/{catId}/{title}"
private const val ROUTE_TXN_CARD  = "txncard/{accountId}/{title}"
private const val ROUTE_TXN_MERCHANT = "txnmerchant/{q}/{title}"
private const val ROUTE_REWARD_DETAIL = "rewarddetail/{bank}/{last4}"

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String?): String = if (s == null) "" else URLDecoder.decode(s, "UTF-8")

@Composable
fun AppShell(
    app: LedgerNoirApp,
    smsPermissionGranted: Boolean,
    pendingRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
    pendingAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val token by app.tokenStore.token.collectAsState(initial = "")

    // When a token exists (fresh sign-in OR app relaunch) land on the PostSignIn
    // gate, which fetches the profile and routes to onboarding (no PAN) or the
    // dashboard. Routing straight to Dashboard here would skip the profile check
    // (the onSignedIn → PostSignIn navigation races with this token-driven
    // recomposition, so the gate could be bypassed).
    val startDestination = if (token.isBlank()) Screen.SignIn.route else Screen.PostSignIn.route

    // Reactive sign-out: if the session token is cleared (e.g. the interceptor wiped it
    // after a 401), route back to the sign-in screen and clear the back stack.
    LaunchedEffect(token) {
        if (token.isBlank() && navController.currentDestination?.route != Screen.SignIn.route) {
            navController.navigate(Screen.SignIn.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Deep link: a tapped notification carries a target route. Navigate once the
    // user is signed in and past the gate (so a cold start lands correctly), then
    // tell the host to consume it so it doesn't re-fire on recomposition.
    val currentRoute = navController
        .currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(pendingRoute, currentRoute) {
        val target = pendingRoute
        val gateRoutes = setOf(Screen.SignIn.route, Screen.PostSignIn.route, Screen.Onboarding.route)
        if (target != null && currentRoute != null && currentRoute !in gateRoutes) {
            navController.navigate(target) {
                popUpTo(Screen.Dashboard.route)
                launchSingleTop = true
            }
            onRouteConsumed()
        }
    }

    // Anchor (item id to scroll to on a target screen). Fed by BOTH the deep-link
    // notification extra (captured below) and an in-app notification-center tap
    // (set at navigation time, see the Notifications composable). Target screens
    // will read `activeAnchor` and call `consumeAnchor` once they've scrolled —
    // Money Radar is wired below; Bills/Rewards/Budget wiring is a follow-up task.
    var activeAnchor by remember { mutableStateOf<String?>(null) }
    val consumeAnchor = { activeAnchor = null }
    LaunchedEffect(pendingAnchor) {
        if (pendingAnchor != null) {
            activeAnchor = pendingAnchor
            onAnchorConsumed()
        }
    }

    val bottomNavItems = listOf(
        Screen.Dashboard    to Icons.Default.Home,
        Screen.Transactions to Icons.Default.List,
        Screen.Insights     to Icons.Default.BarChart,
        Screen.Savings      to Icons.Default.Savings,
        Screen.Portfolio    to Icons.Default.TrendingUp,
        Screen.Bills        to Icons.Default.ReceiptLong,
    )

    ProvideFormFactor {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            val navBackStack by navController.currentBackStackEntryAsState()
            val currentDest = navBackStack?.destination
            val hideBottomBar = currentDest?.route == Screen.SignIn.route ||
                currentDest?.route == Screen.PostSignIn.route ||
                currentDest?.route == Screen.Onboarding.route ||
                currentDest?.route == ROUTE_TXN_RANGE ||
                currentDest?.route == ROUTE_TXN_CAT ||
                currentDest?.route == ROUTE_TXN_CARD ||
                currentDest?.route == ROUTE_TXN_MERCHANT ||
                currentDest?.route == Screen.NetWorth.route ||
                currentDest?.route == Screen.Spending.route ||
                currentDest?.route == Screen.SaveMore.route ||
                currentDest?.route == Screen.Notifications.route ||
                currentDest?.route == Screen.MoneyRadar.route ||
                currentDest?.route == "rewards" ||
                currentDest?.route == ROUTE_REWARD_DETAIL
            if (!hideBottomBar) {
                NavigationBar(
                    containerColor = CanvasBlack,
                    tonalElevation = 0.dp,
                ) {
                    val ff = LocalFormFactor.current
                    bottomNavItems.forEach { (screen, icon) ->
                        NavigationBarItem(
                            icon     = { Icon(icon, contentDescription = screen.label) },
                            label = if (ff == FormFactor.Cover) null else {
                                {
                                    Text(
                                        screen.label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            letterSpacing = 0.sp,
                                        ),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            },
                            alwaysShowLabel = ff != FormFactor.Cover,
                            selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentTeal,
                                selectedTextColor = AccentTeal,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = SurfaceMid,
                            ),
                            onClick  = {
                                navController.navigate(screen.route) {
                                    // Anchor tabs to Dashboard (the home tab). The graph's
                                    // start destination is the PostSignIn gate, which is
                                    // popped after routing, so it can't be the anchor.
                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = startDestination,
            modifier         = Modifier.padding(innerPadding),
        ) {
            composable(Screen.SignIn.route) {
                SignInScreen(
                    repository = app.repository,
                    tokenStore = app.tokenStore,
                    onSignedIn = {
                        // Route through a gate that fetches the profile and decides
                        // whether onboarding (no PAN) or the dashboard is next.
                        navController.navigate(Screen.PostSignIn.route) {
                            popUpTo(Screen.SignIn.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.PostSignIn.route) {
                // Gate screen: decide the post-sign-in destination.
                LaunchedEffect(Unit) {
                    val profile = app.repository.getProfile().getOrNull()
                    val dest = if (profile != null && !profile.hasPan) {
                        Screen.Onboarding.route
                    } else {
                        Screen.Dashboard.route
                    }
                    navController.navigate(dest) {
                        popUpTo(Screen.PostSignIn.route) { inclusive = true }
                    }
                }
                Box(
                    modifier = Modifier.fillMaxSize().background(CanvasBlack),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            }
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    repository = app.repository,
                    onDone = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    app                  = app,
                    repository           = app.repository,
                    smsPermissionGranted = smsPermissionGranted,
                    onOpenRange          = { start, end, title ->
                        navController.navigate("txnrange/$start/$end/${enc(title)}")
                    },
                    onOpenCategory       = { start, end, catId, title ->
                        navController.navigate("txncat/$start/$end/$catId/${enc(title)}")
                    },
                    onOpenSettings       = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onOpenNetWorth       = {
                        navController.navigate(Screen.NetWorth.route)
                    },
                    onOpenNotifications  = {
                        navController.navigate(Screen.Notifications.route)
                    },
                    onOpenMoneyRadar     = {
                        navController.navigate(Screen.MoneyRadar.route)
                    },
                    onOpenRewards        = { navController.navigate("rewards") },
                )
            }
            composable(Screen.NetWorth.route) {
                NetWorthScreen(repository = app.repository, onBack = { navController.popBackStack() })
            }
            composable(Screen.Notifications.route) {
                NotificationsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRoute = { route, anchor ->
                        // Capture the tapped item's anchor so the target screen can
                        // scroll to it; target screens will consume it (follow-up task).
                        activeAnchor = anchor
                        navController.navigate(route) {
                            popUpTo(Screen.Dashboard.route)
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Screen.MoneyRadar.route) {
                MoneyRadarScreen(
                    repository = app.repository,
                    onDrillDown = { merchant, _ ->
                        // Findings that carry a merchant (mandates, duplicates, large
                        // charges, price hikes, forgotten recurring) open that merchant's
                        // transaction list. Aggregate findings (forex markup, card costs)
                        // have no merchant and don't navigate.
                        if (!merchant.isNullOrBlank()) {
                            navController.navigate("txnmerchant/${enc(merchant)}/${enc(merchant)}")
                        }
                    },
                    onBack = { navController.popBackStack() },
                    scrollAnchor = activeAnchor,
                    onAnchorConsumed = consumeAnchor,
                )
            }
            composable(Screen.AiInsights.route) {
                AiInsightsScreen()
            }
            composable(Screen.Transactions.route) {
                TransactionsScreen(repository = app.repository)
            }
            composable(Screen.Insights.route) {
                InsightsScreen(
                    repository       = app.repository,
                    onOpenCategory   = { start, end, catId, title ->
                        navController.navigate("txncat/$start/$end/$catId/${enc(title)}")
                    },
                    onOpenSpending   = { navController.navigate(Screen.Spending.route) },
                    onOpenSaveMore   = { navController.navigate(Screen.SaveMore.route) },
                    onOpenBudget     = { navController.navigate(Screen.Budget.route) },
                    onOpenMoneyRadar = { navController.navigate(Screen.MoneyRadar.route) },
                    onOpenAiInsights = { navController.navigate(Screen.AiInsights.route) },
                    onOpenRewards    = { navController.navigate("rewards") },
                )
            }
            composable(Screen.Spending.route) {
                SpendingInsightsScreen(
                    repository = app.repository,
                    onOpenCategory = { start, end, catId, title ->
                        navController.navigate("txncat/$start/$end/$catId/${enc(title)}")
                    },
                )
            }
            composable(Screen.SaveMore.route) {
                SaveMoreScreen(repository = app.repository)
            }
            composable(Screen.Portfolio.route) {
                PortfolioScreen(repository = app.repository)
            }
            composable(Screen.Savings.route) {
                SavingsScreen(repository = app.repository)
            }
            composable(Screen.Bills.route) {
                BillsScreen(
                    repository = app.repository,
                    onOpenCard = { accountId, title ->
                        navController.navigate("txncard/$accountId/${enc(title)}")
                    },
                    onOpenRewards = { navController.navigate("rewards") },
                    scrollAnchor = activeAnchor,
                    onAnchorConsumed = consumeAnchor,
                )
            }
            composable(Screen.Budget.route) {
                BudgetScreen(
                    repository = app.repository,
                    scrollAnchor = activeAnchor,
                    onAnchorConsumed = consumeAnchor,
                )
            }
            composable("rewards") {
                RewardsScreen(
                    repository = app.repository,
                    onBack = { navController.popBackStack() },
                    onOpenCard = { bank, last4 -> navController.navigate("rewarddetail/$bank/$last4") },
                    scrollAnchor = activeAnchor,
                    onAnchorConsumed = consumeAnchor,
                )
            }
            composable(
                route = ROUTE_REWARD_DETAIL,
                arguments = listOf(
                    navArgument("bank") { type = NavType.StringType },
                    navArgument("last4") { type = NavType.StringType },
                ),
            ) { entry ->
                RewardDetailScreen(
                    repository = app.repository,
                    bank = entry.arguments?.getString("bank") ?: "",
                    last4 = entry.arguments?.getString("last4") ?: "",
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(tokenStore = app.tokenStore, repository = app.repository)
            }

            composable(
                route = ROUTE_TXN_RANGE,
                arguments = listOf(
                    navArgument("start") { type = NavType.StringType },
                    navArgument("end")   { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                ),
                enterTransition = {
                    slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))
                },
                exitTransition = { fadeOut(tween(200)) },
                popExitTransition = {
                    slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300))
                },
            ) { entry ->
                val args = entry.arguments
                Last3BreakdownScreen(
                    repository = app.repository,
                    title      = dec(args?.getString("title")),
                    startIso   = args?.getString("start") ?: "",
                    endIso     = args?.getString("end") ?: "",
                    onBack     = { navController.popBackStack() },
                )
            }

            composable(
                route = ROUTE_TXN_CAT,
                arguments = listOf(
                    navArgument("start") { type = NavType.StringType },
                    navArgument("end")   { type = NavType.StringType },
                    navArgument("catId") { type = NavType.IntType },
                    navArgument("title") { type = NavType.StringType },
                ),
                enterTransition = {
                    slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))
                },
                exitTransition = { fadeOut(tween(200)) },
                popExitTransition = {
                    slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300))
                },
            ) { entry ->
                val args = entry.arguments
                TransactionListScreen(
                    repository = app.repository,
                    title      = dec(args?.getString("title")),
                    startIso   = args?.getString("start") ?: "",
                    endIso     = args?.getString("end") ?: "",
                    categoryId = args?.getInt("catId"),
                    onBack     = { navController.popBackStack() },
                )
            }

            // Per-card transaction drill-down (all of a credit card's spend,
            // last 12 months). Reached by tapping a card on the Bills tab.
            composable(
                route = ROUTE_TXN_CARD,
                arguments = listOf(
                    navArgument("accountId") { type = NavType.IntType },
                    navArgument("title")     { type = NavType.StringType },
                ),
                enterTransition = {
                    slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))
                },
                exitTransition = { fadeOut(tween(200)) },
                popExitTransition = {
                    slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300))
                },
            ) { entry ->
                val args = entry.arguments
                val today = java.time.LocalDate.now()
                TransactionListScreen(
                    repository = app.repository,
                    title      = dec(args?.getString("title")),
                    startIso   = today.minusYears(1).toString(),
                    endIso     = today.plusDays(1).toString(),
                    accountId  = args?.getInt("accountId"),
                    onBack     = { navController.popBackStack() },
                )
            }

            // Transactions for a single merchant — Money Radar finding drill-down.
            // All-time, both directions (the screen handles scoping via `query`).
            composable(
                route = ROUTE_TXN_MERCHANT,
                arguments = listOf(
                    navArgument("q")     { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                ),
                enterTransition = {
                    slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))
                },
                exitTransition = { fadeOut(tween(200)) },
                popExitTransition = {
                    slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300))
                },
            ) { entry ->
                val args = entry.arguments
                TransactionListScreen(
                    repository = app.repository,
                    title      = dec(args?.getString("title")),
                    startIso   = "",
                    endIso     = "",
                    query      = dec(args?.getString("q")),
                    onBack     = { navController.popBackStack() },
                )
            }
        }
    }
    }
}
