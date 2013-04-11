import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.*

import javax.swing.*
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicReference

import static intellijeval.PluginUtil.*

def statsWriter = new StatsWriter(pluginPath)
def isTrackingVarName = "WhatIWorkOnStats.isTracking"

if (isIdeStartup && !getGlobalVar(isTrackingVarName)) {
	setGlobalVar(isTrackingVarName, true)
	startTrackingWhatIsGoingOn(statsWriter, isTrackingVarName)
	show("Tracking current file: ON")
}

registerAction("WhatIWorkOnStats", "ctrl shift alt O") { AnActionEvent actionEvent ->
	JBPopupFactory.instance.createActionGroupPopup(
			"Current file statistics",
			new DefaultActionGroup().with{
				add(new AnAction() {
					@Override void actionPerformed(AnActionEvent event) {
						def trackingIsOn = changeGlobalVar(isTrackingVarName, false){ !it }
						if (trackingIsOn) startTrackingWhatIsGoingOn(statsWriter, isTrackingVarName)
						show("Tracking current file: " + (trackingIsOn ? "ON" : "OFF"))
					}

					@Override void update(AnActionEvent event) {
						def isTracking = getGlobalVar(isTrackingVarName, false)
						event.presentation.text = (isTracking ? "Stop tracking current file" : "Start tracking current file")
					}
				})
				add(new AnAction("Analyze history") {
					@Override void actionPerformed(AnActionEvent event) {
						show("Analyze history") // TODO
					}
				})
				add(new AnAction("Reset history") {
					@Override void actionPerformed(AnActionEvent event) {
						show("Reset history") // TODO
					}
				})
				it
			},
			actionEvent.dataContext,
			JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
			true
	).showCenteredInCurrentWindow(actionEvent.project)
}
show("reloaded")

void startTrackingWhatIsGoingOn(StatsWriter statsWriter, String isTrackingVarName) {
	new Thread({
		while (getGlobalVar(isTrackingVarName)) {
			AtomicReference logEvent = new AtomicReference<LogEvent>()
			SwingUtilities.invokeAndWait { logEvent.set(createLogEvent(new Date())) }
			if (logEvent != null) statsWriter.append(logEvent.get().toCsv())
			Thread.sleep(1000)
		}
	} as Runnable).start()
}

class StatsWriter {
	private final String statsFilePath

	StatsWriter(String path) {
		this.statsFilePath = path + "/stats.csv"
	}

	def append(String csvLine) {
		new File(statsFilePath).append(csvLine + "\n")
	}
}

LogEvent createLogEvent(Date now) {
	IdeFrame activeFrame = WindowManager.instance.allProjectFrames.find{it.active}
	// this tracks project frame as inactive during refactoring
	// (e.g. when "Rename class" frame is active)
	if (activeFrame == null) return new LogEvent(now, "", "", "")
	def project = activeFrame.project
	def editor = currentEditorIn(project)
	if (editor == null) return new LogEvent(now, project.name, "", "")

	def elementAtOffset = currentPsiFileIn(project)?.findElementAt(editor.caretModel.offset)
	PsiMethod psiMethod = findParent(elementAtOffset, {it instanceof PsiMethod})
	PsiFile psiFile = findParent(elementAtOffset, {it instanceof PsiFile})
	def currentElement = (psiMethod == null ? psiFile : psiMethod)

	// this doesn't take into account time spent in toolwindows
	// (when the same frame is active but editor doesn't have focus)
	def file = currentFileIn(project)
	def filePath = (file == null ? "" : file.path.replace(project.basePath, ""))
	new LogEvent(now, project.name, filePath, fullNameOf(currentElement))
}

private static String fullNameOf(PsiElement psiElement) {
	if (psiElement == null || psiElement instanceof PsiFile) ""
	else if (psiElement in PsiAnonymousClass) {
		def parentName = fullNameOf(psiElement.parent)
		def name = "[" + psiElement.baseClassType.className + "]"
		parentName.empty ? name : (parentName + "::" + name)
	} else if (psiElement instanceof PsiMethod || psiElement instanceof PsiClass) {
		def parentName = fullNameOf(psiElement.parent)
		parentName.empty ? psiElement.name : (parentName + "::" + psiElement.name)
	} else {
		fullNameOf(psiElement.parent)
	}
}

private def <T> T findParent(PsiElement element, Closure matches) {
	if (element == null) null
	else if (matches(element)) element as T
	else findParent(element.parent, matches)
}

@groovy.transform.Immutable
final class LogEvent {
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("kk:mm:ss dd/MM/yyyy")
	Date time
	String projectName
	String file
	String element

	String toCsv() {
		"${TIME_FORMAT.format(time)},$projectName,$file,$element"
	}
}