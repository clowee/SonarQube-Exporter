import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.json.simple.parser.ParseException
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder


val parser = JSONParser()
val sonarInstance = "http://sonar.inf.unibz.it"

private val MAX_URL_LENGTH = 2000
private val MAX_ELASTICSEARCH_RESULTS = 10000

fun main(args: Array<String>) {

    try {

        val metricKeys = getMetricKeys()
        val ruleKeys = getRuleKeys()

        //save current csv for QC projects
        val projectKeys = getProjectsContainingString("QC - col")//QC - aspectj, QC - jboss, QC - jtopen
        //println("projects: ${projectKeys.size}")
        saveCurrentMeasuresAndIssues("current-measures-and-issues.csv", projectKeys, metricKeys, ruleKeys)


        //save history csv for "org.apache:commons-cli"
        val projectKey = "org.apache:commons-cli"
        saveNonemptyPastMeasures("nonempty-past-measures.txt", projectKey, metricKeys)
        val usefulMetricKeys = readListFromFile("nonempty-past-measures.txt")
        saveMeasureHistory("measures.csv", projectKey, usefulMetricKeys)
        saveIssues("issues.csv", projectKey, "CLOSED,OPEN", ruleKeys)
        mergeMeasuresWithIssues("measures.csv", "issues.csv", "measures-and-issues.csv")

        //saveJiraIssues("jira-issues.csv", "CLI")

        //saveGitCommits()

    } catch (e: ParseException) {
        println("JSON parsing error")
        e.printStackTrace()
    }
}

/*
Returns a list of project keys containing a string
 */
fun  getProjectsContainingString(partOfName: String): List<String> {
    val query = "http://sonar.inf.unibz.it/api/components/search" +
            "?qualifiers=TRK" +
            "&ps=1000" +
            "&q=${URLEncoder.encode(partOfName, "UTF-8")}"
    val response = getStringFromUrl(query)
    val mainObject = parser.parse(response) as JSONObject
    val componentArray = mainObject["components"] as JSONArray
    return componentArray.filterIsInstance<JSONObject>().map { it["key"].toString() }
}

/*
Saves in a .csv file all of the current measures and issues for given projects
 */
private fun saveCurrentMeasuresAndIssues(fileName: String, projectKeys: List<String>, metricKeys: List<String>, ruleKeys: List<String>) {
    val measureKeys = mutableSetOf<String>()
    val issueKeys = mutableSetOf<String>()
    val allProjectMeasures = mutableListOf<Map<String,String>>()
    val allProjectIssues = mutableListOf<Map<String,Int>>()
    for (projectKey in projectKeys) {
        //get measures
        val measureValues = mutableMapOf<String, String>()
        val metricKeysLeft = metricKeys.toMutableList()
        measureKeys.add("_project")
        measureValues.put("_project", projectKey)
        val measureQuery = "$sonarInstance/api/measures/component" +
                "?componentKey=$projectKey" +
                "&metricKeys="
        while (!metricKeysLeft.isEmpty()) {
            var query = measureQuery
            while (!metricKeysLeft.isEmpty() && (query.length + metricKeysLeft.first().length < MAX_URL_LENGTH)) {
                if (query == measureQuery)
                    query += metricKeysLeft.removeAt(0)
                else
                    query += "," + metricKeysLeft.removeAt(0)
            }
            val measureResult = getStringFromUrl(query)
            val mainObject = parser.parse(measureResult) as JSONObject
            val componentObject = mainObject["component"] as JSONObject
            val measureArray = componentObject["measures"] as JSONArray
            for (metricObject in measureArray.filterIsInstance<JSONObject>()) {
                val measureKey = metricObject["metric"].toString()
                val measureValue = metricObject["value"].toString().replace(",", ";")
                measureKeys.add(measureKey)
                measureValues.put(measureKey, measureValue)
            }
        }
        allProjectMeasures.add(measureValues)

        //save issues to file
        saveIssues("current-issues.csv", projectKey, "OPEN", ruleKeys)

        //read issues from file
        val issueCount = mutableMapOf<String, Int>()
        val issueCSV = readListFromFile("current-issues.csv")
        for (line in issueCSV.subList(1, issueCSV.size)) {
            val ruleKey = line.split(",")[2]
            issueKeys.add(ruleKey)
            val previousCount = issueCount.getOrDefault(ruleKey, 0)
            issueCount[ruleKey] = previousCount + 1
        }
        allProjectIssues.add(issueCount)
    }
    BufferedWriter(FileWriter(fileName)).use { bw ->
        //header
        val sortedMeasureKeys = measureKeys.toSortedSet()
        val sortedIssueKeys = issueKeys.toSortedSet()
        bw.write(sortedMeasureKeys.joinToString(","))
        bw.write(",")
        bw.write(sortedIssueKeys.joinToString(","))
        bw.newLine()

        //rows
        for ((idx, measureValues) in allProjectMeasures.withIndex()) {
            val issueValues = allProjectIssues[idx]

            val rowMeasures = sortedMeasureKeys.map { measureValues[it] ?: "null" }
            val rowIssues = sortedIssueKeys.map { issueValues[it] ?: 0 }
            bw.write(rowMeasures.joinToString(","))
            bw.write(",")
            bw.write(rowIssues.joinToString(","))
            bw.newLine()
        }
    }
    println("Measures and issues saved to $fileName")
}


/*
Tests which past measures contain nonempty values for a given project.
Stores the result in file.
 */
private fun saveNonemptyPastMeasures(fileName: String, projectKey: String, metricKeys: List<String>) {
    val usefulMeasures = mutableListOf<String>()
    val measureQuery = "$sonarInstance/api/measures/search_history" +
            "?component=$projectKey" +
            "&metrics="
    val metricKeysLeft = metricKeys.toMutableList()
    while (!metricKeysLeft.isEmpty()) {
        var query = measureQuery
        while (!metricKeysLeft.isEmpty() && (query.length + metricKeysLeft.first().length < MAX_URL_LENGTH)) {
            if (query == measureQuery)
                query += metricKeysLeft.removeAt(0)
            else
                query += "," + metricKeysLeft.removeAt(0)
        }
        val measureResult = getStringFromUrl(query)
        val measureObject = parser.parse(measureResult) as JSONObject
        val pagingObject = measureObject["paging"] as JSONObject
        val measureCount = Integer.parseInt(pagingObject["total"].toString())
        println("Measures found: $measureCount")
        val measureArray = measureObject["measures"] as JSONArray
        for (metricObject in measureArray.filterIsInstance<JSONObject>()) {
            val measureKey = metricObject["metric"].toString()
            val measureHistory = metricObject["history"] as JSONArray
            if (measureHistory.isEmpty())
                println("$measureKey : empty")
            else {
                println("$measureKey : values=${measureHistory.size}")
                if (measureHistory.size > 1) {// && measureKey != "quality_profiles" && measureKey != "quality_gate_details") {
                    /*if (measureKey == "quality_gate_details") {
                        usefulMeasures.add("$measureKey-op")
                        usefulMeasures.add("$measureKey-actual")
                        usefulMeasures.add("$measureKey-period")
                        usefulMeasures.add("$measureKey-metric")
                        usefulMeasures.add("$measureKey-level")
                        usefulMeasures.add("$measureKey-error")
                        usefulMeasures.add("$measureKey-warning")
                    }*/
                    usefulMeasures.add(measureKey)
                }
            }
        }
    }
    println("Nonempty past measures: ${usefulMeasures.size}")

    BufferedWriter(FileWriter(fileName)).use { bw ->
        for (key in usefulMeasures) {
            bw.write(key)
            bw.newLine()
        }
    }
}

/*
Returns all metrics available on the server
 */
private fun getMetricKeys(): List<String> {
    val metricsQuery = "$sonarInstance/api/metrics/search?ps=1000"
    val metricsResult = getStringFromUrl(metricsQuery)
    val metricsObject = parser.parse(metricsResult) as JSONObject
    val metricsCount = Integer.parseInt(metricsObject["total"].toString())
    println("Metrics found: $metricsCount")
    val metricsKeys = mutableListOf<String>()
    val metricsArray = metricsObject["metrics"] as JSONArray
    metricsArray.filterIsInstance<JSONObject>().mapTo(metricsKeys) { it["key"].toString() }
    println(metricsKeys)
    assert(metricsKeys.size == metricsCount)
    return metricsKeys
}

/*
Saves past measures measures for a project in a .csv file
 */
private fun saveMeasureHistory(fileName: String, projectKey: String, metricKeys: List<String>) {
    val measureQuery = "$sonarInstance/api/measures/search_history" +
            "?component=" + projectKey +
            "&ps=1000" +
            "&metrics=" + metricKeys.joinToString(",")

    val measureResult = getStringFromUrl(measureQuery)
    val measureObject = parser.parse(measureResult) as JSONObject
    val pagingObject = measureObject["paging"] as JSONObject
    val measureCount = Integer.parseInt(pagingObject["total"].toString())
    println("Measures found: $measureCount")
    val measureArray = measureObject["measures"] as JSONArray
    val measureMap = sortedMapOf<String, Array<String>>()

    for (metricObject in measureArray.filterIsInstance<JSONObject>()) {
        val measureKey = metricObject["metric"].toString()
        val measureHistory = metricObject["history"] as JSONArray
        for (measureEntry in measureHistory.filterIsInstance<JSONObject>()) {
            val date = measureEntry["date"].toString()
            val value = measureEntry["value"].toString()
            if (!measureMap.containsKey(date)) {
                measureMap.put(date, Array(metricKeys.size, init = { _ -> "0" }))
            }
            val valueArray = measureMap[date]
            //if (measureKey == "quality_profiles" || measureKey == "quality_gate_details")
            valueArray!![metricKeys.indexOf(measureKey)] = value.replace(",", ";")
        }
    }
    BufferedWriter(FileWriter(fileName)).use { bw ->
        val columns: List<String> = listOf<String>("date") + metricKeys
        bw.write(columns.joinToString(","))
        bw.newLine()
        for ((key, values) in measureMap) {
            bw.write(key + "," + values.joinToString(","))
            bw.newLine()
        }
    }
}

/*
Saves issue history for a project in a .csv file
 */
private fun saveIssues(fileName: String, projectKey: String, statuses: String, ruleKeys: List<String>) {
    val rows = mutableListOf<String>()
    for (splitKeys in splitIntoBatches(ruleKeys, 40)) {
        saveIssueRows(projectKey, statuses, splitKeys, rows)
    }

    BufferedWriter(FileWriter(fileName)).use { bw ->
        val header = "creation_date,update_date,rule,component"
        bw.write(header)
        bw.newLine()
        rows.map {
            bw.write(it)
            bw.newLine()
        }
        println("Issues saved to $fileName")
    }
}

private fun saveIssueRows(componentKey: String, statuses: String, ruleKeys: List<String>, rows: MutableList<String>) {
    val pageSize = 500
    var currentPage = 1
    var issuesQuery = "$sonarInstance/api/issues/search" +
            "?componentKeys=$componentKey" +
            "&s=CREATION_DATE" +
            "&statuses=$statuses" +
            "&rules=${ruleKeys.joinToString(",")}" +
            "&ps=$pageSize" +
            "&p=$currentPage"
    val sonarResult = getStringFromUrl(issuesQuery)
    var mainObject = parser.parse(sonarResult) as JSONObject
    println(mainObject["total"].toString() + "    $componentKey")
    if (Integer.valueOf(mainObject["total"].toString()) > MAX_ELASTICSEARCH_RESULTS) {
        // if result size is too big, split it
        for (splitKeys in splitIntoBatches(ruleKeys, ruleKeys.size/2)) {
            saveIssueRows(componentKey, statuses, splitKeys, rows)
        }
    } else {
        // save results
        var issuesArray = mainObject["issues"] as JSONArray
        var issuesArraySize = issuesArray.size
        //println("Saving $issuesArraySize/${mainObject["total"]}")
        while (issuesArraySize > 0) {
            // save row data
            for (issueObject in issuesArray.filterIsInstance<JSONObject>()) {
                val creationDate = issueObject["creationDate"].toString()
                val updateDate = issueObject["updateDate"].toString()
                val rule = issueObject["rule"].toString()
                val component = issueObject["component"].toString()
                //val classname = component.replaceFirst((projectKey + ":").toRegex(), "")
                val classname = component
                val status = issueObject["status"].toString()

                val closedDate = if (status == "CLOSED")
                    updateDate
                else
                    ""
                val rowItems = mutableListOf<String>(creationDate, closedDate, rule, classname)
                rows.add(rowItems.joinToString(","))
            }
            // get next page
            currentPage++
            issuesQuery = "$sonarInstance/api/issues/search" +
                    "?componentKeys=$componentKey" +
                    "&s=CREATION_DATE" +
                    "&statuses=$statuses" +
                    "&rules=${ruleKeys.joinToString(",")}" +
                    "&ps=$pageSize" +
                    "&p=$currentPage"
            val result = getStringFromUrl(issuesQuery)
            mainObject = parser.parse(result) as JSONObject
            issuesArray = mainObject["issues"] as JSONArray
            issuesArraySize = issuesArray.size
        }
    }
}

/**
 * Splits a list into many smaller lists.
 * @param batchSize maximum size for the smaller lists
 */
fun splitIntoBatches(list: List<String>, batchSize: Int): List<List<String>> {
    val result = mutableListOf<List<String>>()
    var start = 0
    var end = batchSize
    while (start < list.size) {
        if (end > list.size)
            end = list.size
        result.add(list.subList(start, end))
        start = end
        end += batchSize
    }
    return result
}

/*
Get a list of rule keys for language java
 */
private fun getRuleKeys(): List<String> {
    val result = mutableListOf<String>()
    val pageSize = 500
    var page = 0
    do {
        page++
        val query = "http://sonar.inf.unibz.it/api/rules/search" +
                "?ps=$pageSize" +
                "&p=$page" +
                "&f=lang" +
                "&languages=java"//"&languages=web"
        val response = getStringFromUrl(query)
        val mainObject = parser.parse(response) as JSONObject
        val ruleArray = mainObject["rules"] as JSONArray
        result.addAll(
                ruleArray.filterIsInstance<JSONObject>().map { it["key"].toString() })
        val lastItem = page * pageSize
    } while (Integer.parseInt(mainObject["total"].toString()) > lastItem)
    return result
}

/*
Merges the list of issues with the measure history, grouping issues by date
 */
private fun mergeMeasuresWithIssues(measuresFile: String, issuesFile: String, combinedFile: String) {
    val ruleKeys = mutableSetOf<String>()
    val issuesByDateOpened = mutableMapOf<String, MutableList<String>>()
    val issuesByDateClosed = mutableMapOf<String, MutableList<String>>()

    val issueCSV = readListFromFile(issuesFile)
    for (line in issueCSV.subList(1, issueCSV.size)) {
        val creation_date = line.split(",")[0]
        val update_date = line.split(",")[1]
        val ruleKey = line.split(",")[2]
        //val component = line.split(",")[3]
        ruleKeys.add(ruleKey)

        issuesByDateOpened.computeIfAbsent(creation_date, { _ -> mutableListOf() })
                .add(ruleKey)
        issuesByDateClosed.computeIfAbsent(update_date, { _ -> mutableListOf() })
                .add(ruleKey)
    }

    val measureCSV = readListFromFile(measuresFile)

    println("Measure cols: ${measureCSV[0].split(",").size}")
    println("Issue Cols: ${ruleKeys.size}")
    BufferedWriter(FileWriter(combinedFile)).use { bw ->
        bw.write(measureCSV[0] + "," + ruleKeys.joinToString(","))
        bw.newLine()


        val currentIssueCount = mutableMapOf<String, String>()
        for (ruleKey in ruleKeys) {
            currentIssueCount[ruleKey] = "0"
        }

        for (line in measureCSV.subList(1, measureCSV.size)) {
            val measureDate = line.split(",")[0]
            val openedIssues = issuesByDateOpened.getOrDefault(measureDate, mutableListOf())
            for (ruleKey in openedIssues) {
                currentIssueCount[ruleKey] = (Integer.valueOf(currentIssueCount[ruleKey]!!) + 1).toString()
            }

            val closedIssues = issuesByDateClosed.getOrDefault(measureDate, mutableListOf())
            for (ruleKey in closedIssues) {
                currentIssueCount[ruleKey] = (Integer.valueOf(currentIssueCount[ruleKey]!!) - 1).toString()
            }

            bw.write(line + "," + currentIssueCount.values.joinToString(","))
            bw.newLine()
        }
    }
}

/*
Parses an URL request as a string
 */
fun getStringFromUrl(queryURL: String): String {
    assert(queryURL.length <= MAX_URL_LENGTH) // URLS over 2000 are not supported
    println("\nSending 'GET' request to URL : " + queryURL)
    val url = URL(queryURL)
    val con = url.openConnection() as HttpURLConnection
    con.requestMethod = "GET"
    val responseCode = con.responseCode
    println("Response Code : " + responseCode)

    val `in` = BufferedReader(InputStreamReader(con.inputStream))
    val stringBuilder = StringBuilder()
    do {
        val inputLine = `in`.readLine()
        if (inputLine != null) {
            stringBuilder.append(inputLine)
        }
    } while (inputLine != null)
    return stringBuilder.toString()
}

/*
Reads each line from file into a string list
 */
private fun readListFromFile(filename: String): List<String> {
    val result = mutableListOf<String>()
    val file = File(filename)
    try {
        BufferedReader(FileReader(file)).use { br ->
            do {
                val line = br.readLine()
                if (line != null)
                    result.add(line)
            } while (line != null)
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return result
}
