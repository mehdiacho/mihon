package mihon.feature.quicksearch

import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Fork: global search on top of whatever app is in front, without opening Mihon (mihon#12).
 *
 * A translucent activity in a task of its own, so the app underneath stays where it was and comes back when this
 * finishes. Opened from the accessibility shortcut, the Quick Settings tile, or "Search in" on selected text.
 * It starts as a search bar; a search turns it into a drawer running the app's own global search, where a manga
 * can be added, opened and read. "Open in app" hands over to the real app at the same place.
 */
class QuickSearchActivity : BaseActivity() {

    /** Null while the bar is showing, the searched text once the drawer is up. */
    private var query by mutableStateOf<String?>(null)

    /** Bumped for each new search from outside, so the drawer starts over with it. */
    private var searchId by mutableStateOf(0)

    private val host = QuickSearchHost(onDismiss = ::close)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BaseActivity applies the user's app theme, which is opaque. The manifest theme is what lets the app
        // underneath show; this keeps the window itself see-through after the switch.
        theme.applyStyle(R.style.ThemeOverlay_Tachiyomi_QuickSearch, true)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        registerSecureActivity(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        query = savedInstanceState?.getString(KEY_QUERY) ?: intent.searchText()
        host.query = query.orEmpty()

        setComposeContent {
            val current = query
            val scrimAlpha by animateFloatAsState(if (current == null) 0.32f else 0.5f, label = "scrim")
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor.Black.copy(alpha = scrimAlpha)),
            ) {
                if (current == null) {
                    SearchBar(
                        onSearch = { text ->
                            host.query = text
                            query = text
                        },
                        onDismiss = ::close,
                    )
                } else {
                    key(searchId) {
                        Drawer(current)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val text = intent.searchText() ?: return
        host.query = text
        query = text
        searchId++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        query?.let { outState.putString(KEY_QUERY, host.query.ifBlank { it }) }
    }

    /**
     * The overlay keeps everything it opens in its own task. The reader becomes [OverlayReaderActivity], which
     * stacks on top of the drawer instead of jumping to the app's task, and anything aimed at the app itself
     * (the expand button, "open in app" from deeper screens) moves to the app's task and closes the overlay.
     */
    override fun startActivity(intent: Intent, options: Bundle?) {
        when (intent.component?.className) {
            ReaderActivity::class.java.name -> intent.setClass(this, OverlayReaderActivity::class.java)
            MainActivity::class.java.name -> {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                super.startActivity(intent, options)
                close()
                return
            }
        }
        super.startActivity(intent, options)
    }

    @Composable
    private fun SearchBar(onSearch: (String) -> Unit, onDismiss: () -> Unit) {
        var text by rememberSaveable { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null, onClick = {}),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(MR.strings.quick_search_hint)) },
                    leadingIcon = { Icon(MaterialSymbols.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { if (text.isEmpty()) onDismiss() else text = "" }) {
                            Icon(
                                MaterialSymbols.Rounded.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { text.trim().takeIf(String::isNotEmpty)?.let(onSearch) },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ComposeColor.Transparent,
                        unfocusedContainerColor = ComposeColor.Transparent,
                        focusedIndicatorColor = ComposeColor.Transparent,
                        unfocusedIndicatorColor = ComposeColor.Transparent,
                    ),
                )
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    @Composable
    private fun Drawer(searched: String) {
        AdaptiveSheet(
            isTabletUi = false,
            enableImplicitDismiss = true,
            onDismissRequest = ::close,
            modifier = Modifier.fillMaxHeight(DRAWER_HEIGHT),
        ) {
            Navigator(GlobalSearchScreen(searched)) { navigator ->
                host.navigator = navigator
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 4.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = ::close) {
                            Icon(
                                MaterialSymbols.Rounded.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                            )
                        }
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Surface(
                                modifier = Modifier
                                    .size(width = 32.dp, height = 4.dp)
                                    .alpha(0.4f),
                                shape = RoundedCornerShape(2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ) {}
                        }
                        IconButton(onClick = ::openInApp) {
                            Icon(
                                MaterialSymbols.AutoMirroredRounded.OpenInNew,
                                contentDescription = stringResource(MR.strings.quick_search_open_in_app),
                            )
                        }
                    }
                    CompositionLocalProvider(LocalQuickSearchHost provides host) {
                        DefaultNavigatorScreenTransition(navigator = navigator)
                    }
                }
            }
        }
    }

    /** The full app, at the manga on screen, or at global search for the same text. */
    private fun openInApp() {
        val intent = Intent(this, MainActivity::class.java)
        when (val screen = host.navigator?.lastItem) {
            is MangaScreen -> intent.setAction(Constants.SHORTCUT_MANGA).putExtra(Constants.MANGA_EXTRA, screen.mangaId)
            else -> intent.setAction(MainActivity.INTENT_SEARCH)
                .putExtra(MainActivity.INTENT_SEARCH_QUERY, host.query.ifBlank { query.orEmpty() })
        }
        startActivity(intent)
    }

    private fun close() {
        if (isFinishing) return
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        const val KEY_QUERY = "quick_search_query"
        const val DRAWER_HEIGHT = 0.94f
        const val MAX_QUERY_CHARS = 200

        /** Text handed over by "Search in" on a selection, a share, or a search intent. */
        fun Intent.searchText(): String? =
            (
                getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    ?: getStringExtra(SearchManager.QUERY)
                    ?: getCharSequenceExtra(Intent.EXTRA_TEXT)
                )
                ?.toString()
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(MAX_QUERY_CHARS)
                ?.takeIf(String::isNotEmpty)
    }
}
