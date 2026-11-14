package mihon.feature.quicksearch

import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * The reader, opened from the quick search drawer. Upstream's reader is `singleTask` in the app's own task, so
 * opening it from the overlay would pull the whole app forward. This one is declared in the overlay's task: it
 * stacks on top of the drawer, and back returns to the drawer, with the other app still underneath.
 */
class OverlayReaderActivity : ReaderActivity() {

    /** "Open manga" and friends go to the real app, in its own task, and the overlay goes away. */
    override fun startActivity(intent: Intent, options: Bundle?) {
        val toApp = intent.component?.className == MainActivity::class.java.name
        if (toApp) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        super.startActivity(intent, options)
        if (toApp) finishAffinity()
    }
}
