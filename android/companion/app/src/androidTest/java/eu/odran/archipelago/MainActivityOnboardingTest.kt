package eu.odran.archipelago

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityOnboardingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearFirstRunState() {
        context.getSharedPreferences("first_run_guide", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("joined_archipelago_room", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun quickStartCanBeDismissedAndStaysDismissed() {
        launchMain().use { scenario ->
            onView(withText(R.string.quick_start_title)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                activity.findButton(R.string.quick_start_dismiss).performClick()
            }
            onView(withText(R.string.quick_start_title)).check(matches(withEffectiveVisibility(GONE)))
        }

        launchMain().use {
            onView(withText(R.string.quick_start_title)).check(doesNotExist())
            onView(withText(R.string.no_active_room)).check(matches(isDisplayed()))
        }
    }

    private fun launchMain(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_UI_TEST_MODE, true),
    )

    private fun MainActivity.findButton(label: Int): Button {
        val expected = getString(label)
        fun findIn(group: ViewGroup): Button? {
            for (index in 0 until group.childCount) {
                when (val child = group.getChildAt(index)) {
                    is Button -> if (child.text.toString() == expected) return child
                    is ViewGroup -> findIn(child)?.let { return it }
                }
            }
            return null
        }
        return requireNotNull(findIn(findViewById(android.R.id.content))) {
            "Could not find button labelled $expected"
        }
    }
}
