package dev.andrea.perroquet

import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class InstructionNavigationTest {

    @Test
    fun `test initial button states`() {
        ActivityScenario.launch(InstructionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val previousButton = activity.findViewById<Button>(R.id.previousButton)
                val nextButton = activity.findViewById<Button>(R.id.nextButton)
                val startButton = activity.findViewById<Button>(R.id.startExperimentButton)
                
                // On first page, previous should be invisible, next visible, start gone
                assertEquals(View.INVISIBLE, previousButton.visibility)
                assertEquals(View.VISIBLE, nextButton.visibility)
                assertEquals(View.GONE, startButton.visibility)
            }
        }
    }
    
    @Test
    fun `test navigation to second page`() {
        ActivityScenario.launch(InstructionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewPager = activity.findViewById<ViewPager2>(R.id.instructionViewPager)
                val previousButton = activity.findViewById<Button>(R.id.previousButton)
                val nextButton = activity.findViewById<Button>(R.id.nextButton)
                
                // Navigate to second page
                activity.runOnUiThread {
                    viewPager.currentItem = 1
                }
                
                // On second page, both previous and next should be visible
                assertEquals(View.VISIBLE, previousButton.visibility)
                assertEquals(View.VISIBLE, nextButton.visibility)
            }
        }
    }
    
    @Test
    fun `test navigation to last page`() {
        ActivityScenario.launch(InstructionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewPager = activity.findViewById<ViewPager2>(R.id.instructionViewPager)
                val previousButton = activity.findViewById<Button>(R.id.previousButton)
                val nextButton = activity.findViewById<Button>(R.id.nextButton)
                val startButton = activity.findViewById<Button>(R.id.startExperimentButton)
                
                // Navigate to last page
                activity.runOnUiThread {
                    viewPager.currentItem = 2 // Last page (0-indexed)
                }
                
                // On last page, previous should be visible, next gone, start visible
                assertEquals(View.VISIBLE, previousButton.visibility)
                assertEquals(View.GONE, nextButton.visibility)
                assertEquals(View.VISIBLE, startButton.visibility)
            }
        }
    }
}
