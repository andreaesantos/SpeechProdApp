package dev.andrea.perroquet

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate
import java.util.UUID

class InstructionActivity : AppCompatActivity() {

    private var participantId: Int = -1
    private var sessionNumber: Int = 1
    private var dateString: String = ""
    private var runId: String = ""
    private var mode: String = ParticipantInputActivity.MODE_FULL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instruction)

        participantId = intent.getIntExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID, -1)
        dateString = intent.getStringExtra(ParticipantInputActivity.EXTRA_DATE) ?: LocalDate.now().toString()
        runId = intent.getStringExtra(ParticipantInputActivity.EXTRA_RUN_ID) ?: UUID.randomUUID().toString()
        mode = intent.getStringExtra(ParticipantInputActivity.EXTRA_MODE) ?: ParticipantInputActivity.MODE_FULL


        val icon = findViewById<ImageView>(R.id.instructionIcon)
        val nextButton = findViewById<Button>(R.id.nextButton)

        // Tap the icon to start
        icon.setOnClickListener {
            navigateToExperiment()
        }

        nextButton.setOnClickListener {navigateToExperiment()}
    }

    private fun navigateToExperiment() {
        val i = Intent(this, ExperimentActivity::class.java).apply {
            putExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID, participantId)
            putExtra(ParticipantInputActivity.EXTRA_DATE, dateString)
            putExtra(ParticipantInputActivity.EXTRA_RUN_ID, runId)
            putExtra(ParticipantInputActivity.EXTRA_MODE, mode)
        }
        startActivity(i)
        finish()
    }
}
//package dev.andrea.perroquet
//
//import android.content.Intent
//import android.os.Bundle
//import android.view.View
//import android.widget.Button
//import android.widget.ImageView
//import android.widget.LinearLayout
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.content.ContextCompat
//import androidx.viewpager2.widget.ViewPager2
//import java.time.LocalDate
//
//class InstructionActivity : AppCompatActivity() {
//
//    private lateinit var viewPager: ViewPager2
//    private lateinit var previousButton: Button
//    private lateinit var nextButton: Button
//    private lateinit var startExperimentButton: Button
//    private lateinit var pageIndicatorLayout: LinearLayout
//
//    // Text-only instruction page
//    data class InstructionPage(val text: String)
//
//    private val instructionPages = listOf(
//        InstructionPage(
//            text = "Bienvenue dans l'expérience !\n\n" +
//                    "Dans cette tâche, vous allez regarder de courtes vidéos, puis nous dire ce qui s’est passé — un peu comme si vous racontiez une vidéo amusante que vous avez vue en ligne à un(e) ami(e) !\n\n" +
//                    "Il n’y a pas de bonnes ou de mauvaises réponses. Nous nous intéressons simplement à la manière dont vous décrivez naturellement ce que vous avez vu.\n\n" +
//                    "L'expérience est divisée en 40 blocs courts, et chaque bloc contient 10 vidéos. Vous pouvez faire une pause entre les blocs à tout moment.\n"
//        ),
//        InstructionPage(
//            text = "Chaque essai suit cette séquence simple :\n\n" +
//                    "1. Regardez une vidéo de 4 secondes\n" +
//                    "2. Attendez 2 secondes (une courte pause)\n" +
//                    "3. Décrivez ce qui s’est passé — parlez à voix haute.\n\n" +
//                    "Essayez de décrire la vidéo avec vos propres mots, comme si vous en parliez à quelqu’un qui ne l’a pas vue.\n"
//        ),
//        InstructionPage(
//            text = "Les descriptions pourraient ressembler à ceci :\n\n" +
//                    "1. Un homme en chemise verte joue d’une guitare noire\n" +
//                    "2. Un troupeau de vaches se promène sur une prairie\n" +
//                    "3. Quelqu’un frappe un arbre près du trottoir avec un bâton\n\n" +
//                    "Plus la description est détaillée, mieux c’est !"
//        ),
//        InstructionPage(
//            text = "Lorsque vous êtes prêt(e), appuyez sur Démarrer pour commencer l’expérience.\n\nBonne chance !"
//        )
//    )
//
//    private var participantId: Int = -1
//
//    private var sessionNumber: Int = 1
//    private var dateString: String = ""
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_instruction)
//
//        participantId = intent.getIntExtra("PARTICIPANT_ID", -1)
//        sessionNumber = intent.getIntExtra("SESSION_NUMBER", 1)
//        dateString = intent.getStringExtra("DATE") ?: LocalDate.now().toString()
//
//        viewPager = findViewById(R.id.instructionViewPager)
//        previousButton = findViewById(R.id.previousButton)
//        nextButton = findViewById(R.id.nextButton)
//        startExperimentButton = findViewById(R.id.startExperimentButton)
//        pageIndicatorLayout = findViewById(R.id.pageIndicator)
//
//        // IMPORTANT: you must also update InstructionPagerAdapter to accept List<InstructionPage(text only)>
//        val adapter = InstructionPagerAdapter(instructionPages)
//        viewPager.adapter = adapter
//
//        setupPageIndicator()
//        setupButtonListeners()
//        setupPageChangeCallback()
//    }
//
//    private fun setupPageIndicator() {
//        for (i in instructionPages.indices) {
//            val dot = ImageView(this)
//            dot.setImageDrawable(
//                ContextCompat.getDrawable(
//                    this,
//                    if (i == 0) android.R.drawable.ic_menu_more
//                    else android.R.drawable.ic_menu_close_clear_cancel
//                )
//            )
//
//            val params = LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.WRAP_CONTENT,
//                LinearLayout.LayoutParams.WRAP_CONTENT
//            )
//            params.setMargins(8, 0, 8, 0)
//            dot.layoutParams = params
//
//            pageIndicatorLayout.addView(dot)
//        }
//    }
//
//    private fun setupButtonListeners() {
//        previousButton.setOnClickListener {
//            val currentItem = viewPager.currentItem
//            if (currentItem > 0) viewPager.currentItem = currentItem - 1
//        }
//
//        nextButton.setOnClickListener {
//            val currentItem = viewPager.currentItem
//            if (currentItem < instructionPages.size - 1) viewPager.currentItem = currentItem + 1
//        }
//
//        startExperimentButton.setOnClickListener {
//            navigateToExperiment()
//        }
//    }
//
//    private fun setupPageChangeCallback() {
//        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
//            override fun onPageSelected(position: Int) {
//                updatePageIndicator(position)
//                updateButtonVisibility(position)
//            }
//        })
//    }
//
//    private fun updatePageIndicator(position: Int) {
//        for (i in 0 until pageIndicatorLayout.childCount) {
//            val dot = pageIndicatorLayout.getChildAt(i) as ImageView
//            dot.setImageDrawable(
//                ContextCompat.getDrawable(
//                    this,
//                    if (i == position) android.R.drawable.ic_menu_more
//                    else android.R.drawable.ic_menu_close_clear_cancel
//                )
//            )
//        }
//    }
//
//    private fun updateButtonVisibility(position: Int) {
//        previousButton.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
//
//        if (position == instructionPages.size - 1) {
//            nextButton.visibility = View.GONE
//            startExperimentButton.visibility = View.VISIBLE
//        } else {
//            nextButton.visibility = View.VISIBLE
//            startExperimentButton.visibility = View.GONE
//        }
//    }
//
//    private fun navigateToExperiment() {
//        val i = Intent(this, ExperimentActivity::class.java).apply {
//            putExtra("PARTICIPANT_ID", participantId)
//            putExtra("SESSION_NUMBER", sessionNumber)
//            putExtra("DATE", dateString)
//        }
//        startActivity(i)
//        finish()
//    }
//}
