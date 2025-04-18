package com.valonso.jadx.fingerprinting

import app.revanced.patcher.Fingerprint
import com.android.tools.smali.dexlib2.analysis.reflection.util.ReflectionUtils
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.valonso.jadx.fingerprinting.solver.Solver
import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.api.JavaClass
import jadx.api.metadata.ICodeNodeRef
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.gui.JadxGuiContext
import jadx.core.dex.info.MethodInfo
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.gui.ui.MainWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.io.ByteArrayInputStream
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.util.function.Function
import javax.swing.*
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object RevancedFingerprintPluginUi {

    private val LOG = KotlinLogging.logger("${RevancedFingerprintPlugin.ID}/ui")
    private lateinit var context: JadxPluginContext
    private lateinit var guiContext: JadxGuiContext

    val revancedSvg = """
        <svg role="img" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" id="revanced" height="16" width="16">
            <path d="M5.1 0a0.28 0.28 0 0 0 -0.23 0.42l6.88 11.93a0.28 0.28 0 0 0 0.48 0L19.13 0.42A0.28 0.28 0 0 0 18.9 0ZM0.5 0a0.33 0.33 0 0 0 -0.3 0.46L10.43 23.8c0.05 0.12 0.17 0.2 0.3 0.2h2.54c0.13 0 0.25 -0.08 0.3 -0.2L23.8 0.46a0.33 0.33 0 0 0 -0.3 -0.46h-2.32a0.24 0.24 0 0 0 -0.21 0.14L12.2 20.08a0.23 0.23 0 0 1 -0.42 0L3.03 0.14A0.23 0.23 0 0 0 2.82 0Z" fill="#000000" stroke-width="1"></path>
        </svg>
    """.trimIndent()
    val playArrowSvg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><!-- Icon from Material Symbols by Google - https://github.com/google/material-design-icons/blob/master/LICENSE --><path fill="currentColor" d="M8 19V5l11 7z"/></svg>
    """.trimIndent()
    val contentCopySvg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 24 24"><!-- Icon from Material Symbols by Google - https://github.com/google/material-design-icons/blob/master/LICENSE --><path fill="currentColor" d="M9 18q-.825 0-1.412-.587T7 16V4q0-.825.588-1.412T9 2h9q.825 0 1.413.588T20 4v12q0 .825-.587 1.413T18 18zm-4 4q-.825 0-1.412-.587T3 20V6h2v14h11v2z"/></svg>
    """.trimIndent()
    private val frameName = "Revanced Fingerprint Evaluator"
    private var fingerprintEvalFrame: JFrame? = null
    private val minimalSetsFrameName = "Fingerprinting Results"

    fun init(context: JadxPluginContext) {
        this.context = context
        this.guiContext = context.guiContext!!
        SwingUtilities.invokeLater {
            try {
                //Remove all frames with the title "Revanced Script Evaluator"
                JFrame.getFrames().filter { it.title == frameName }.forEach { it.dispose() }
                JFrame.getFrames().filter { it.title == minimalSetsFrameName }.forEach { it.dispose() }
                addToolbarButton()
                addCopyFingerprintAction()
            } catch (e: Exception) {
                LOG.error(e) { "Failed to initialize UI" }
                JOptionPane.showMessageDialog(
                    guiContext.mainFrame,
                    "Failed to initialize Revanced Fingerprint Plugin UI: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    fun isCopyFingerprintActionEnabled(codeNodeRef: ICodeNodeRef): Boolean {
        return codeNodeRef is MethodNode
    }

    fun copyFingerprintAction(codeNodeRef: ICodeNodeRef) {
        try {
            val methodNode = codeNodeRef as MethodNode
            val methodInfo = methodNode.methodInfo
            LOG.info { "Generating fingerprints for method: ${methodInfo.shortId}" }
            val uniqueMethodId = "${ReflectionUtils.javaToDexName(methodNode.parentClass.rawName)}${methodInfo.shortId}"
            try {
                val minimalSets = Solver.getMinimalDistinguishingFeatures(uniqueMethodId)
                if (minimalSets.isEmpty()) {
                    LOG.warn { "No feature sets found for method $uniqueMethodId" }
                    JOptionPane.showMessageDialog(
                        guiContext.mainFrame,
                        "Could not find any distinguishing feature sets for this method.",
                        "No Sets Found",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                // Show the window with all minimal sets
                showMinimalSetsWindow(minimalSets, methodNode)

            } catch (e: IllegalStateException) {
                LOG.error(e) { "Failed to find feature sets for $uniqueMethodId" }
                JOptionPane.showMessageDialog(
                    guiContext.mainFrame,
                    "Failed to generate fingerprints: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            } catch (e: Exception) {
                LOG.error(e) { "Failed during fingerprint generation or display for $uniqueMethodId" }
                JOptionPane.showMessageDialog(
                    guiContext.mainFrame,
                    "An unexpected error occurred: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        } catch (e: Exception) {
            LOG.error(e) { "Failed to process method node for fingerprinting" }
            JOptionPane.showMessageDialog(
                guiContext.mainFrame,
                "Failed to get method details: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    fun showMinimalSetsWindow(
        minimalSets: List<List<String>>, methodNode: MethodNode
    ) {
        val methodShortId = methodNode.methodInfo.shortId
        val uniqueMethodId = "${ReflectionUtils.javaToDexName(methodNode.parentClass.rawName)}${methodShortId}"
        val methodFeatures = Solver.getMethodFeatures(uniqueMethodId)
        val fullMethodFingerprint = Solver.featuresToFingerprintString(methodFeatures)
        SwingUtilities.invokeLater {
            // Close existing window if open
            JFrame.getFrames().find { it.title == minimalSetsFrameName }?.dispose()

            val frame = JFrame(minimalSetsFrameName)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.setSize(700, 500)
            frame.setLocationRelativeTo(guiContext.mainFrame)


            val mainPanel = JPanel(GridBagLayout()) // Changed layout
            mainPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

            val gbc = GridBagConstraints()
            gbc.gridx = 0 // All components in the first column
            gbc.gridy = GridBagConstraints.RELATIVE // Place components below each other
            gbc.weightx = 1.0 // Allow horizontal stretching
            gbc.fill = GridBagConstraints.HORIZONTAL // Fill available horizontal space
            gbc.anchor = GridBagConstraints.NORTHWEST // Anchor to top-left
            gbc.insets = Insets(0, 0, 0, 0) // Default spacing


            val titleLabel =
                JTextArea("Found ${minimalSets.size} fingerprint(s) for method : ${uniqueMethodId}")
            titleLabel.isEditable = false
            titleLabel.lineWrap = true
            titleLabel.wrapStyleWord = true
            titleLabel.preferredSize = Dimension(0, 50)

            gbc.insets = Insets(0, 0, 10, 0) // Add bottom margin
            mainPanel.add(titleLabel, gbc)


            val fullFingerprintPanel = JPanel(BorderLayout(5, 5))
            fullFingerprintPanel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Full Method Fingerprint | ${methodFeatures.size} feature(s) "),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
            )

            val fullTextArea = JTextArea(fullMethodFingerprint)
            fullTextArea.isEditable = false
            fullTextArea.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            fullTextArea.tabSize = 2

            val fullCopyButton = JButton(null, inlineSvgIcon(contentCopySvg))
            fullCopyButton.toolTipText = "Copy the full method fingerprint to clipboard"
            fullCopyButton.addActionListener {
                try {
                    guiContext.copyToClipboard(fullMethodFingerprint)
                    LOG.info { "Copied full method fingerprint to clipboard." }
                    fullCopyButton.isEnabled = false
                    Timer(1500) {
                        fullCopyButton.isEnabled = true
                    }.apply { isRepeats = false }.start()
                } catch (e: Exception) {
                    LOG.error(e) { "Failed to copy full fingerprint string to clipboard" }
                    JOptionPane.showMessageDialog(
                        frame,
                        "Failed to copy to clipboard: ${e.message}",
                        "Copy Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }

            fullFingerprintPanel.add(fullTextArea, BorderLayout.CENTER)
            fullFingerprintPanel.add(fullCopyButton, BorderLayout.EAST)

            gbc.insets = Insets(0, 0, 15, 0)
            mainPanel.add(fullFingerprintPanel, gbc)


            mainPanel.add(Box.createRigidArea(Dimension(0, 15)))


            minimalSets.forEachIndexed { index, featureSet ->
                val fingerprintString = try {
                    Solver.featuresToFingerprintString(featureSet)
                } catch (e: Exception) {
                    LOG.error(e) { "Failed to convert feature set to string: $featureSet" }
                    "Error generating fingerprint string: ${e.message}"
                }

                val setPanel = JPanel(BorderLayout(5, 5)) // Panel for each set
                setPanel.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Fingeprint ${index + 1} | ${featureSet.size} feature(s) "),
                    BorderFactory.createEmptyBorder(5, 5, 5, 5)
                )

                val textArea = JTextArea(fingerprintString)
                textArea.isEditable = false
                textArea.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                textArea.tabSize = 2

                val copyButton = JButton(null, inlineSvgIcon(contentCopySvg))
                copyButton.toolTipText = "Copy this fingerprint to clipboard"
                copyButton.addActionListener {
                    try {
                        guiContext.copyToClipboard(fingerprintString)
                        LOG.info { "Copied fingerprint set ${index + 1} to clipboard." }
                        copyButton.isEnabled = false
                        Timer(1500) {
                            copyButton.isEnabled = true
                        }.apply { isRepeats = false }.start()

                    } catch (e: Exception) {
                        LOG.error(e) { "Failed to copy fingerprint string to clipboard" }
                        JOptionPane.showMessageDialog(
                            frame,
                            "Failed to copy to clipboard: ${e.message}",
                            "Copy Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }

                setPanel.add(textArea, BorderLayout.CENTER)
                setPanel.add(copyButton, BorderLayout.EAST)

                gbc.insets = Insets(0, 0, 10, 0)
                mainPanel.add(setPanel, gbc)
            }
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.VERTICAL
            mainPanel.add(Box.createVerticalGlue(), gbc)

            val containerPanel = JPanel(BorderLayout())
            containerPanel.add(mainPanel, BorderLayout.NORTH)

            val scrollPane = JScrollPane(containerPanel)
            scrollPane.verticalScrollBar.unitIncrement = 16

            frame.contentPane.add(scrollPane)
            frame.isVisible = true
        }
    }


    fun addCopyFingerprintAction() {
        guiContext.addPopupMenuAction(
            "Copy Revanced fingerprint",
            ::isCopyFingerprintActionEnabled,
            "R",
            ::copyFingerprintAction
        )
    }

    private fun addToolbarButton() {
        try {
            val mainFrame = guiContext.mainFrame ?: run {
                LOG.warn { "Could not get main frame" }
                return
            }
            val mainPanel = getMainPanelReflectively(mainFrame) ?: run {
                LOG.warn { "Could not get main panel via reflection" }
                return
            }

            // Find the toolbar (assuming it's the component at index 2 in mainPanel's NORTH region)
            // This is fragile and depends on JADX internal layout
            var northPanel = mainPanel.components.find { comp ->
                mainPanel.layout is BorderLayout && (mainPanel.layout as BorderLayout).getConstraints(comp) == BorderLayout.NORTH
            }

            if (northPanel !is JToolBar) {
                // Fallback: Try the example's direct index approach if BorderLayout failed
                if (mainPanel.componentCount > 2 && mainPanel.getComponent(2) is JToolBar) {
                    northPanel = mainPanel.getComponent(2) as JToolBar
                } else {
                    LOG.warn { "Could not find JToolBar in main panel's NORTH region or at index 2. Found: ${northPanel?.javaClass?.name}" }
                    return
                }
            }

            val toolbar = northPanel
            val scriptButtonName = "${RevancedFingerprintPlugin.ID}.button"
            // Re-initialize the plugin button since if not there are classpath shenanigans
            toolbar.components.find { it.name == scriptButtonName }?.let {
                LOG.info { "Removing existing button from toolbar." }
                toolbar.remove(it)
            }

            val icon = inlineSvgIcon(revancedSvg)
            val button = JButton(null, icon)
            button.name = scriptButtonName
            button.toolTipText = "Open Revanced Fingerprint Evaluator"

            button.addActionListener {
                LOG.info { "Toolbar button clicked, showing UI." }
                if (fingerprintEvalFrame != null) {
                    fingerprintEvalFrame?.requestFocus()
                } else {
                    showScriptPanel()
                }
            }

            val preferencesIndex = toolbar.components.indexOfFirst { it.name?.contains("preferences") == true }
                .let { if (it == -1) toolbar.componentCount - 2 else it + 2 }
            toolbar.add(button, preferencesIndex) // Add after preferences button
            toolbar.revalidate()
            toolbar.repaint()
            LOG.info { "Added fingerprint evaluator button to toolbar." }

        } catch (e: Exception) {
            LOG.error(e) { "Failed to add button to toolbar" }
        }
    }

    // Helper function using reflection (similar to the Java example)
    private fun getMainPanelReflectively(frame: JFrame): JPanel? {
        return try {
            val field: Field = frame::class.java.getDeclaredField("mainPanel")
            field.isAccessible = true
            field.get(frame) as? JPanel
        } catch (e: Exception) {
            LOG.error(e) { "Failed to get mainPanel field via reflection" }
            null
        }
    }

    fun showScriptPanel() {

        SwingUtilities.invokeLater {
            val frame = JFrame(frameName)
            fingerprintEvalFrame = frame
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(e: java.awt.event.WindowEvent?) {
                    fingerprintEvalFrame = null
                }
            })
            frame.setSize(800, 600)
            frame.setLocationRelativeTo(guiContext.mainFrame) // Center relative to main frame

            // Main panel with BorderLayout contains the CodePanel in the CENTER region.
            val mainPanel = JPanel(BorderLayout())
            mainPanel.border = BorderFactory.createEmptyBorder(10, 0, 0, 0) // Add padding
            val codePanel = CodePanel().apply {
                preferredSize = Dimension(600, 400)
            }
            try {
                val editorTheme = (guiContext.mainFrame as MainWindow).editorTheme
                codePanel.setTheme(editorTheme)
            } catch (e: Exception) {
                LOG.error(e) { "Failed to set theme for CodePanel" }
            }
            mainPanel.add(codePanel, BorderLayout.WEST)

            val icon = inlineSvgIcon(playArrowSvg) as Icon
            val resultPanel = JPanel(BorderLayout())
            val resultHeaderPanel = JPanel(BorderLayout())
            resultHeaderPanel.border = BorderFactory.createEmptyBorder(0, 10, 10, 0) // Add padding
            val runButton = JButton(null, icon).apply {
                toolTipText = "Run the script"
                margin = Insets(3, 3, 3, 3)
                preferredSize = Dimension(icon.iconWidth, icon.iconHeight)
                maximumSize = preferredSize
                border = BorderFactory.createEmptyBorder(3, 3, 3, 3) // Remove default border
            }

            resultHeaderPanel.add(runButton, BorderLayout.WEST)
            //add a label to the right
            val resultLabel = JLabel("Fingerprint result")
            resultLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 0) // Add padding
            resultHeaderPanel.add(resultLabel, BorderLayout.CENTER)
            resultPanel.add(resultHeaderPanel, BorderLayout.NORTH)

            val resultContentPanel = JPanel()
            resultContentPanel.layout = BorderLayout()

            val resultContentBox = Box.createVerticalBox()
            resultContentPanel.add(resultContentBox, BorderLayout.PAGE_START)


            val resultScrollPane = JScrollPane(
                resultContentPanel
            )
            resultPanel.add(resultScrollPane, BorderLayout.CENTER)
            mainPanel.add(resultPanel, BorderLayout.CENTER)

            runButton.addActionListener {
                runButton.isEnabled = false
                resultContentBox.removeAll()
                val statusLabel = JLabel("Evaluating...")
                statusLabel.alignmentX = Component.LEFT_ALIGNMENT
                resultContentBox.add(statusLabel)
                resultContentBox.revalidate()
                resultContentBox.repaint()

                // Get script text from CodePanel's CodeArea
                val script = codePanel.getText()

                // Launch evaluation in a background thread using Coroutines
                GlobalScope.launch(Dispatchers.IO) { // Use Dispatchers.IO for blocking tasks
                    LOG.info { "Evaluating script: $script" }
                    var result: ResultWithDiagnostics<EvaluationResult>? = null
                    var evaluationError: Throwable? = null
                    val executionTime = measureTime {
                        try {
                            result = ScriptEvaluation.rawEvaluate(script)
                        } catch (t: Throwable) {
                            evaluationError = t
                            LOG.error(t) { "Exception during script evaluation" }
                        }
                    }

                    // Prepare result components (JLabels) in the background
                    val resultComponents = mutableListOf<Component>()
                    val outputBuilder = StringBuilder() // For logging or alternative display

                    if (evaluationError != null) {
                        val errorMsg = "Evaluation failed: ${evaluationError.message}"
                        resultComponents.add(createWrappedTextArea(errorMsg))
                        outputBuilder.appendLine(errorMsg)
                        // Optionally add stack trace details
                    } else if (result != null) {
                        when (val evalResult = result!!) {
                            is ResultWithDiagnostics.Failure -> {
                                val failMsg = "Script evaluation failed:"
                                resultComponents.add(createWrappedTextArea(failMsg))
                                outputBuilder.appendLine(failMsg)
                                ScriptEvaluation.LOG.error { failMsg }
                                evalResult.reports.forEach { report ->
                                    val message = "  ${report.severity}: ${report.message}"
                                    resultComponents.add(createWrappedTextArea(message))
                                    outputBuilder.appendLine(message)
                                    ScriptEvaluation.LOG.error { message }
                                    report.exception?.let {
                                        ScriptEvaluation.LOG.error(it) { "  Exception details:" }
                                        // Optionally add exception details to outputBuilder/components
                                    }
                                }
                            }

                            is ResultWithDiagnostics.Success -> {
                                when (val returnValue = evalResult.value.returnValue) {
                                    ResultValue.NotEvaluated -> {
                                        val msg = "Script was not evaluated."
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                    }

                                    is ResultValue.Error -> {
                                        val msg = "Script execution error: ${returnValue.error} "
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                        ScriptEvaluation.LOG.error(returnValue.error) { "Script execution error:" }
                                        // Optionally add stack trace
                                    }

                                    is ResultValue.Unit -> {
                                        val msg =
                                            "Script did not produce a value. Result type: ${returnValue::class.simpleName}"
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                        ScriptEvaluation.LOG.warn { msg }
                                    }

                                    is ResultValue.Value -> {
                                        val actualValue = returnValue.value
                                        if (actualValue == null) {
                                            val msg = "Script returned null."
                                            resultComponents.add(createWrappedTextArea(msg))
                                            outputBuilder.appendLine(msg)
                                            ScriptEvaluation.LOG.warn { msg }
                                        } else if (actualValue !is Fingerprint) {
                                            val msg = "Script returned unexpected type: ${returnValue.type}"
                                            resultComponents.add(createWrappedTextArea(msg))
                                            outputBuilder.appendLine(msg)
                                            ScriptEvaluation.LOG.error { msg }
                                            ScriptEvaluation.LOG.error { "Actual value classloader: ${actualValue.javaClass.classLoader}" }
                                            ScriptEvaluation.LOG.error { "Expected Fingerprint classloader: ${Fingerprint::class.java.classLoader}" }
                                        } else {
                                            val resolvedFingerprint = actualValue
                                            val msg = "Fingerprint: $resolvedFingerprint"
                                            outputBuilder.appendLine(msg)
                                            val searchResult = RevancedResolver.searchFingerprint(resolvedFingerprint)
                                            if (searchResult != null) {
                                                outputBuilder.appendLine("Fingerprint found in APK: ${searchResult.definingClass}")
                                                outputBuilder.appendLine(
                                                    "originalFullName: ${
                                                        ReflectionUtils.dexToJavaName(
                                                            searchResult.definingClass
                                                        ).replace("$", ".")
                                                    }"
                                                )
                                                outputBuilder.appendLine("shortId: ${searchResult.getShortId()}")
                                                val javaKlass = context.decompiler.searchJavaClassByOrigFullName(
                                                    ReflectionUtils.dexToJavaName(
                                                        searchResult.definingClass
                                                    ).replace(
                                                        "$",
                                                        "."
                                                    ) // Make sure subclass $ is replaced with dot TODO: this might error if the class name has a $ but what can you do
                                                )
                                                outputBuilder.appendLine("javaKlass: $javaKlass")
                                                val fgMethod =
                                                    javaKlass?.searchMethodByShortId(searchResult.getShortId())
                                                outputBuilder.appendLine("fgMethod: $fgMethod")

                                                fgMethod?.let { sourceMethod ->
                                                    val searchResultMsg =
                                                        "Fingerprint found at method: ${sourceMethod.fullName}"
                                                    outputBuilder.appendLine(searchResultMsg)

                                                    resultComponents.add(createWrappedTextArea(searchResultMsg))
                                                    ScriptEvaluation.LOG.info { searchResultMsg }
                                                    val jumpButton = JButton("Jump to method")
                                                    jumpButton.addActionListener {
                                                        ScriptEvaluation.LOG.info { "Jumping to method: ${sourceMethod.fullName}" }
                                                        val success = guiContext.open(sourceMethod.codeNodeRef)
                                                        if (success) {
                                                            ScriptEvaluation.LOG.info { "Jumped to method: ${sourceMethod.fullName}" }
                                                        } else {
                                                            ScriptEvaluation.LOG.error { "Failed to jump to method: ${sourceMethod.fullName}" }
                                                            resultComponents.add(
                                                                createWrappedTextArea("Failed to jump to method do it manually or something: ${sourceMethod.fullName}")
                                                            )
                                                        }
                                                    }
                                                    resultComponents.add(jumpButton)
                                                }


                                            } else {
                                                val msg = "Fingerprint not found in the APK."
                                                resultComponents.add(createWrappedTextArea(msg))
                                                outputBuilder.appendLine(msg)
                                                ScriptEvaluation.LOG.warn { msg }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val msg = "Evaluation did not produce a result."
                        resultComponents.add(createWrappedTextArea(msg))
                        outputBuilder.appendLine(msg)
                    }

                    // Switch back to the Event Dispatch Thread (EDT) to update the UI
                    withContext(Dispatchers.Swing) {
                        resultContentBox.removeAll() // Remove "Evaluating..." label
                        if (resultComponents.isEmpty()) {
                            resultContentBox.add(createWrappedTextArea(("No output.")))
                        } else {
                            resultComponents.forEach {
                                resultContentBox.add(
                                    it
                                )
                            }
//                            resultContentBox.add(createWrappedTextArea(outputBuilder.toString()))
                        }
                        ScriptEvaluation.LOG.info { "Script evaluation output:\n ${outputBuilder.toString()}" }


                        resultLabel.text = "Executed in ${executionTime.inWholeMilliseconds.milliseconds}"
                        runButton.isEnabled = true
                        // Ensure layout updates are processed
                        resultContentBox.revalidate()
                        resultContentBox.repaint()
                        // Scroll to top if needed
                        resultScrollPane.verticalScrollBar.value = resultScrollPane.verticalScrollBar.minimum
                        LOG.info { "Script evaluation UI updated." }
                    }
                }
            }
            frame.contentPane = mainPanel
            frame.isVisible = true
        }


    }

    fun dumpClasses() {
        context.decompiler.getRoot().getClasses().forEach {
            val classInfo = it.classInfo
            LOG.info { "package: ${classInfo.`package`}" }
            LOG.info { "isInner: ${classInfo.isInner}" }
            LOG.info { "type: ${classInfo.type}" }
            LOG.info { "parentClass: ${classInfo.parentClass}" }
            LOG.info { "fullName: ${classInfo.fullName}" }
            LOG.info { "rawName: ${classInfo.rawName}" }
            LOG.info { "aliasFullName: ${classInfo.aliasFullName}" }

        }
    }

    fun createWrappedTextArea(text: String): JTextArea {
        val textArea = JTextArea(text)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.isEditable = false // Make read-only
        textArea.alignmentX = Component.LEFT_ALIGNMENT // Align left
        textArea.alignmentY = Component.TOP_ALIGNMENT // Align top
        textArea.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2)) // Add padding
        return textArea
    }

    fun oldStyle() {
        // Ensure UI updates happen on the Event Dispatch Thread
        SwingUtilities.invokeLater {
            // Create the main frame (window)
            val frame = JFrame("Revanced Script Evaluator")
            frame.setSize(800, 600) // Increased size slightly
            // Set location relative to the main JADX window if possible, otherwise center on screen

            // --- Output Panel Setup ---
            val outputPanel = JPanel()
            outputPanel.layout = BoxLayout(outputPanel, BoxLayout.Y_AXIS) // Vertical layout
            outputPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10) // Add padding

            val statusLabel = JLabel("Output will be shown here:")
            statusLabel.alignmentX = Component.LEFT_ALIGNMENT // Align left
            outputPanel.add(statusLabel)

            // Results Area (Bottom, Scrollable)
            val resultsTextArea = JTextArea()
            resultsTextArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            resultsTextArea.isEditable = false // Make read-only
            resultsTextArea.lineWrap = true // Enable line wrapping
            resultsTextArea.wrapStyleWord = true // Wrap at word boundaries
            val resultsScrollPane = JScrollPane(resultsTextArea)
            resultsScrollPane.alignmentX = Component.LEFT_ALIGNMENT // Align left
            // Make the results area take available vertical space (might need tweaking depending on container)
            // resultsScrollPane.preferredSize = Dimension(200, Int.MAX_VALUE) // Adjust width as needed
            // Set preferred width to 50%


            outputPanel.add(resultsScrollPane)
            // --- End Output Panel Setup ---


            // Create the text area for the script
            val scriptTextArea = JTextArea()
            val scriptScrollPane = JScrollPane(scriptTextArea) // Add scroll bars
            scriptScrollPane.setBorder(
                BorderFactory.createCompoundBorder(
                    scriptScrollPane.border,
                    BorderFactory.createEmptyBorder(5, 5, 5, 5)
                )
            );
            scriptScrollPane.minimumSize = Dimension(300, 0) // Set minimum width

            // Create the evaluate button
            val evaluateButton = JButton("Evaluate Script")
            evaluateButton.addActionListener { event: ActionEvent? ->
                // Disable UI elements
                evaluateButton.isEnabled = false
                scriptTextArea.isEnabled = false
                statusLabel.text = "Evaluating..."
                resultsTextArea.text = "" // Clear previous results

                val script = scriptTextArea.text
                LOG.info { "Evaluating script: $script" }
                var result: ResultWithDiagnostics<EvaluationResult>? = null
                var evaluationError: Throwable? = null
                val executionTime = measureTime {
                    result = ScriptEvaluation.rawEvaluate(script)
                }
                // Always update status and re-enable UI, regardless of success/failure/exception
                statusLabel.text = "Executed in ${executionTime.inWholeMilliseconds.milliseconds}"
                evaluateButton.isEnabled = true
                scriptTextArea.isEnabled = true


                val outputBuilder = StringBuilder()

                if (evaluationError != null) {
                    outputBuilder.appendLine("Evaluation failed with exception: ${evaluationError.message}")
                    // Optionally add stack trace or more details
                } else if (result != null) {
                    when (val evalResult = result!!) {
                        is ResultWithDiagnostics.Failure -> {
                            ScriptEvaluation.LOG.error { "Script evaluation failed:" }
                            outputBuilder.appendLine("Script evaluation failed:")
                            evalResult.reports.forEach { report ->
                                val message = "  ${report.severity}: ${report.message}"
                                ScriptEvaluation.LOG.error { message }
                                outputBuilder.appendLine(message)
                                report.exception?.let {
                                    ScriptEvaluation.LOG.error(it) { "  Exception details:" }
                                    // Optionally add exception details to outputBuilder
                                }
                            }
                        }

                        is ResultWithDiagnostics.Success -> {
                            when (val returnValue = evalResult.value.returnValue) {
                                ResultValue.NotEvaluated -> {
                                    outputBuilder.appendLine("Script was not evaluated.")
                                }

                                is ResultValue.Error -> {
                                    ScriptEvaluation.LOG.error(returnValue.error) { "Script execution error:" }
                                    outputBuilder.appendLine("Script execution error: ${returnValue.error}")
                                    // Optionally add stack trace
                                }

                                is ResultValue.Unit -> {
                                    ScriptEvaluation.LOG.warn { "Script did not produce a value result. Result type: ${returnValue::class.simpleName}" }
                                    outputBuilder.appendLine("Script did not produce a value result. Result type: ${returnValue::class.simpleName}")
                                }

                                is ResultValue.Value -> {
                                    ScriptEvaluation.LOG.info { "Script execution result: $returnValue" }
                                    val actualValue = returnValue.value
                                    if (actualValue == null) {
                                        ScriptEvaluation.LOG.warn { "Script returned null." }
                                        outputBuilder.appendLine("Script returned null.")
                                    } else if (actualValue !is Fingerprint) {
                                        ScriptEvaluation.LOG.error { "Script returned unexpected type: ${returnValue.type}" }
                                        ScriptEvaluation.LOG.error { "Actual value classloader: ${actualValue.javaClass.classLoader}" }
                                        ScriptEvaluation.LOG.error { "Expected Fingerprint classloader: ${Fingerprint::class.java.classLoader}" }
                                        outputBuilder.appendLine("Script returned unexpected type: ${returnValue.type}")
                                    } else {
                                        val resolvedFingerprint = actualValue
                                        outputBuilder.appendLine("Fingerprint: $resolvedFingerprint")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    outputBuilder.appendLine("Evaluation did not produce a result.")
                }


                resultsTextArea.text = outputBuilder.toString().trim()
                resultsTextArea.caretPosition = 0 // Scroll to top
                // No need to call revalidate/repaint explicitly here,
                // setting text on Swing components usually handles it.
                LOG.info { "Script evaluation UI updated." }
            }

            val buttonPanel = JPanel()
            buttonPanel.add(evaluateButton)

            // Add components to the frame's content pane
            // Use a SplitPane for resizable areas if desired, or adjust BorderLayout
            frame.contentPane.layout = BorderLayout() // Ensure main layout is BorderLayout
            frame.contentPane.add(scriptScrollPane, BorderLayout.CENTER) // Script input takes center
            frame.contentPane.add(buttonPanel, BorderLayout.SOUTH)      // Button at the bottom
            frame.contentPane.add(outputPanel, BorderLayout.EAST)       // Output on the right

            // Set default close operation (dispose frame, don't exit application)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE

            // Make the window visible
            frame.isVisible = true
        }
    }

    fun inlineSvgIcon(svg: String): FlatSVGIcon {
        val svgInputStream = ByteArrayInputStream(svg.trimIndent().toByteArray(StandardCharsets.UTF_8))
        return FlatSVGIcon(svgInputStream)
    }
}