package gui

import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import java.net.UnknownHostException
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.Priority
import sonarqube.*


private val tableProjects = TableView<SonarProject>()

/**
 * GUI Sonarqube issue/measure extraction
 */
class SonarqubeTab(private val mainGui: MainGui) : Tab("Sonarqube") {

    private val textServer = TextField()

    init {
        val rows = VBox()
        addServerRow(rows)
        addProjectsRow(rows)
        addExportRow(rows)
        this.content = rows
        this.isClosable = false
    }

    private fun addServerRow(rows: VBox) {
        val labelServer = Label("Sonarqube server:")
        textServer.textProperty().addListener({ _, _, newServerString ->
            mainGui.runGuiTask(GetProjectListTask(newServerString))
        })
        textServer.textProperty().set("http://sonar.inf.unibz.it")
        val serverRow = HBoxRow(labelServer, textServer)
        HBox.setHgrow(textServer, Priority.SOMETIMES)
        rows.children.add(serverRow)
    }

    private fun addProjectsRow(rows: VBox) {
        val labelProjects = Label("Projects on server:")
        val keyCol: TableColumn<SonarProject, SonarProject> = TableColumn("key")
        keyCol.cellValueFactory = PropertyValueFactory<SonarProject, SonarProject>("key")
        val nameCol: TableColumn<SonarProject, SonarProject> = TableColumn("name")
        nameCol.cellValueFactory = PropertyValueFactory<SonarProject, SonarProject>("name")

        tableProjects.columns.addAll(keyCol, nameCol)
        tableProjects.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
        // TODO: multiple project selection
        // tableProjects.selectionModel.selectionMode = SelectionMode.MULTIPLE
        tableProjects.selectionModel.selectedItemProperty().addListener { _, oldValue, newValue ->
            println("ListView selection changed from $oldValue to $newValue")
        }

        val projectsRow = HBoxRow(labelProjects, tableProjects)
        HBox.setHgrow(tableProjects, Priority.SOMETIMES)
        rows.children.add(projectsRow)
    }

    private fun addExportRow(rows: VBox) {
        val exportIssuesButton = Button("Export issues")
        exportIssuesButton.setOnAction {
            val selectedProject = tableProjects.selectionModel.selectedItem
            if (selectedProject == null) {
                alertNoProjectSelected()
            } else {
                mainGui.runGuiTask(ExportIssuesTask(selectedProject))
            }
        }
        val exportMeasuresButton = Button("Export measures")
        exportMeasuresButton.setOnAction {
            val selectedProject = tableProjects.selectionModel.selectedItem
            if (selectedProject == null) {
                alertNoProjectSelected()
            } else {
                mainGui.runGuiTask(ExportMeasuresTask(selectedProject))
            }
        }
        val exportRow = HBoxRow(exportIssuesButton, exportMeasuresButton)
        rows.children.add(exportRow)
    }

    private fun alertNoProjectSelected() {
        val alert = Alert(Alert.AlertType.INFORMATION, "Please select a project")
        alert.headerText = null
        alert.showAndWait()
    }
}

/**
 * Queries projects available on Sonarqube server
 */
class GetProjectListTask(private val serverAddress: String) : GuiTask() {

    override fun call(): SonarServer {
        super.call()
        updateMessage("Getting Sonarqube project list")
        val sonarServer = SonarServer(serverAddress)
        try {
            getStringFromUrl(serverAddress)
            getProjectsContainingString(sonarServer, "")
            updateMessage("Retrieved ${sonarServer.projects.size} projects from $serverAddress")
        } catch (e: UnknownHostException) {
            if (!isCancelled) {
                updateMessage("Host $serverAddress not found")
            }
        }
        return sonarServer
    }

    /**
     * Adds the retrieved project list to GUI
     */
    override fun succeeded() {
        val sonarServer: SonarServer = value as SonarServer
        tableProjects.items.clear()
        tableProjects.items.addAll(sonarServer.projects)
    }
}

/**
 * Saves current issues for project
 */
class ExportIssuesTask(private val sonarProject: SonarProject) : GuiTask() {

    override fun call() {
        super.call()
        updateMessage("Exporting issues for ${sonarProject.getName()} (${sonarProject.getKey()})")
        val savedFile = saveIssues(sonarProject, "OPEN")
        updateMessage("Current issues saved to $savedFile")
    }
}


/**
 * Saves current measures for project
 */
class ExportMeasuresTask(private val sonarProject: SonarProject) : GuiTask() {

    override fun call() {
        super.call()
        updateMessage("Exporting measures for ${sonarProject.getName()} (${sonarProject.getKey()})")
        val savedFile = saveMeasures(sonarProject)
        updateMessage("Current measures saved to $savedFile")
    }
}