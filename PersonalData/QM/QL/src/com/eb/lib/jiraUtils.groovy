package com.eb.lib
import com.eb.lib.jobUtils
import groovy.json.JsonSlurperClassic
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor

/**  **********************************************************************************************************
 * Checks if a ticketID can be found in the Gerrit commit Message or the PullRequest title
 *
 * @return ticketID as String
 */
Boolean getJiraIDs(String commitMsg) {
    commitMsg = (new jobUtils().base64Decode(commitMsg).replaceAll('[;,]',' ') =~ /(?i)tracing-id:.*/).findAll()[0]
    def jiraIds = (commitMsg =~ /\w{2,10}-\d{1,6}/).findAll().unique()
    /*if (jiraIds.size()>0){
        manager.createSummary("clipboard.png").appendText("<b>JIRA Tickets:</b> " + jiraIds.join(' '), false, false, false, "black")
    }*/
    return jiraIds
}


/** **********************************************************************************************************
 * Aborts build if triggering Code change is not related to a Jira issue of type Sub-Task
 *
 * @param jiraCredentials for JIRA authentication
 * @param jiraServer url of JIRA server
 * @param astt is a list of strings defining Allowed Sub-Task Types. If empty, all types are allowed
 *
 * @return ticket type as String, e.g. "Task"
 */
def abortIfTriggerIsNotTask(jiraCredentials, jiraServer, astt = [])
{
    ticketID = getJiraIDs()

    echo "Check if triggering code change is related to a Jira issue of type \"Task\" .."
    issueType = getJiraIssueType(ticketID, jiraCredentials, jiraServer)
    if (issueType.equals('Task') || issueType.equals('Sub-task') || issueType.equals('Task Subtask'))
    {
        echo "OK: ${ticketID} is a Task"
        if ( ! astt.isEmpty())
        {
            echo "Check if Sub-Task has valid type .. "
            taskType = getJiraSubTaskType(ticketID, jiraCredentials, jiraServer)
            if ( astt.contains(taskType))
            {
                echo "OK: Sub-Task type of ${ticketID} is ${taskType}"
            }
            else
            {
                manager.createSummary("warning.png").appendText("Triggering Jira issue ${ticketID} has wrong Sub-Task type: \"${taskType}\"",
                                                                false, false, false, "black")
                error "NOK: Wrong Sub-Task type: ${ticketID} is of type \"${taskType}\".\nAllowed types are: ${astt}"
            }
        }
        else
        {
            echo "INFO: Skip check for valid Sub-Task type"
        }
    }
    else
    {
        manager.createSummary("warning.png").appendText("Triggering Jira issue ${ticketID} has wrong Jira issue type: \"${issueType}\".\n${rule}",
                                                        false, false, false, "black")
        error "NOK: ${ticketID} is of type: \"${issueType}\"\n${rule}"
    }
}


/** **********************************************************************************************************
 * Performs GET request
 *
 * @param urlPath is url containing the POST request
 * @param jiraCredentials for JIRA authentication
 * @param jiraServer url of JIRA server
 *
 * @return GET response as json object
 */
def jiraRestRequest(urlPath, jiraCredentials, jiraServer)
{
    withCredentials([usernameColonPassword(credentialsId: jiraCredentials,
                                           variable: 'cred')]) {
        HttpURLConnection connection
        response = null
        try
        {
            connection = "${jiraServer}${urlPath}".toURL().openConnection()
            connection.setConnectTimeout(5000)
            connection.setRequestMethod("GET")
            connection.setRequestProperty("Authorization", "Basic " + "${cred}".getBytes().encodeBase64().toString())
            connection.setRequestProperty("Content-Type", "application/json")
            response = new JsonSlurperClassic().parseText(connection.inputStream.text)
        }
        catch (Exception ex)
        {
            echo ex.toString()
            echo "WARNING: REST GET request failed."
        }
    }
    return response
}


/** **********************************************************************************************************
 * Determines type of a Jira issue, e.g. Story, Epic or Task
 *
 * @param ticketID for which type shall be determined
 * @param jiraCredentials for JIRA authentication
 * @param jiraServer url of JIRA server
 *
 * @return ticket type as String, e.g. "Task"
 */
def getJiraIssueType(ticketID, jiraCredentials, jiraServer)
{
    restAPIResult = jiraRestRequest("/rest/api/2/search?jql=id=${ticketID}&fields=issuetype",
                                    jiraCredentials,
                                    jiraServer)
    try
    {
        ticketType = restAPIResult.issues.fields.issuetype.name.first()
    }
    catch (NoSuchElementException)
    {
        echo "WARNING: Type of ticket ${ticketID} could not be determined."
        echo "GET Response: ${restAPIResult.toString()}"
        ticketType = "unknown"
    }
    return ticketType
}

/** **********************************************************************************************************
 * Determines if Jira issue is labels list contains a given label value
 *
 * @param ticketID the Jira Issue provided in the commit message. Retrieved with getJiraIDs()
 * @param criteria - the label value which is evaluated
 * @param jiraCredentials for JIRA authentication
 * @param jiraServer url of JIRA server
 *
 * @return true if contained
 */
def checkJiraIssueIsCRT(ticketID, criteria, jiraCredentials, jiraServer)
{
    def isCRT = false
    /* This query checks if the issue under investigation has the label value or
     if it is a task, the parent Story has the label value.
     Sample: key=ABC-1234 and ((labels=BRI) or (type=Task and issueFunction in subtasksOf("labels=BRI")))
    */
    restAPIResult = jiraRestRequest("/rest/api/2/search?jql=key%3D${ticketID}%20and%20((labels%3D${criteria})%20or%20(type%3DTask%20and%20issueFunction%20in%20subtasksOf(%22labels%3D${criteria}%22)))&fields=issuetype,component",
                                    jiraCredentials,
                                    jiraServer)
    try
    {
        ticketType = restAPIResult.issues.fields.issuetype.name.first()
        isCRT=true
        echo "The issue ${ticketID} is managed with CRT. '${criteria}' found in issue or in parent."
    }
    catch (NoSuchElementException)
    {
        echo "INFO: Issue ${ticketID} is not handled with CRT (does not contain label '${criteria}')"
        echo "GET Response: ${restAPIResult.toString()}"
    }
    return isCRT
}


/** **********************************************************************************************************
 * Determines type of a Jira Sub-Task, e.g. "Test Code"
 *
 * @param ticketID for which type shall be determined
 * @param jiraCredentials for JIRA authentication
 * @param jiraServer url of JIRA server
 *
 * @return Sub-Task type as String, e.g. "Code"
 */
def getJiraSubTaskType(ticketID, jiraCredentials, jiraServer, jiraField="customfield_16918")
{
    restAPIResult = jiraRestRequest("/rest/api/2/search?jql=id=${ticketID}",
                                    jiraCredentials,
                                    jiraServer)
    try
    {
      taskType = restAPIResult.issues.fields."${jiraField}".value.first()
    }
    catch (NoSuchElementException)
    {
        echo "WARNING: Type of ticket ${ticketID} could not be determined."
        echo "GET Response: ${restAPIResult.toString()}"
        taskType = "None"
    }
    return taskType
}