package mihon.feature.quicksearch

import androidx.compose.runtime.staticCompositionLocalOf
import cafe.adriel.voyager.navigator.Navigator

/**
 * What screens shown inside the quick search drawer need from it. Null everywhere else, so the app's own screens
 * behave exactly as upstream when they aren't in the drawer.
 */
class QuickSearchHost(private val onDismiss: () -> Unit) {

    /** The drawer's navigator, so "open in app" knows which screen is showing. */
    var navigator: Navigator? = null

    /** The last text searched in the drawer, for "open in app" from the results. */
    var query: String = ""

    /** Back from the drawer's first screen closes the drawer rather than doing nothing. */
    fun dismiss() = onDismiss()
}

val LocalQuickSearchHost = staticCompositionLocalOf<QuickSearchHost?> { null }
