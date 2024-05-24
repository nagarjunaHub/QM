package com.eb.lib
import com.eb.lib.gerritUtils
import com.eb.lib.CommonEnvironment
import groovy.json.JsonSlurperClassic
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor

/** **********************************************************************************************************
 * Pretty Log print out for debugging
 * @param msg message to printout
 */
def __INFO(String msg){
    def max=100
    def a = "#"
    def barrierleng = (max-msg.length())/2-1
    barrierleng = (barrierleng < 0) ? 4 : barrierleng
    def barrier = a*barrierleng
    println(barrier + " " + msg + " " + barrier)
}

/** **********************************************************************************************************
 * Delete builds that are in the Jenkins build queue based on Gerrit Review ID (Change Number)
 *
 * @param review_id is the Gerrit review number or GERRIT_CHANGE_NUMBER
 *
 * @return Properties object with accessible attributes/values
 */
def delete_queue_item(String review_id) {
    queue = Hudson.instance.queue
    echo "Queue contains ${queue.items.length} items"

    if (queue != null) {
        for (job in queue.items) {
            params = parsePropertiesString(job.getParams())
            if (params.GERRIT_CHANGE_NUMBER == review_id) {
                echo "Found queued build ${job.getId()} with GERRIT_CHANGE_NUMBER ${review_id}. Canceling..."
                try {
                    queue.cancel(job)
                    echo "Cancel successful."
                } catch (cancel_exception) {
                    echo "WARNING: cancel might have failed - ${cancel_exception}"
                }
            }
        }
    }

    queue = null

}


/** **********************************************************************************************************
 * Parse json into Map to access in pipeline
 *
 * @param json is the json input file
 *
 * @return Map object
 */
@NonCPS
def parseJsonToMap(String json) {
    final slurper = new groovy.json.JsonSlurperClassic()
    return new HashMap<>(slurper.parseText(json))
}

/** **********************************************************************************************************
 * Search element in list
 *
 * @param element is the element
 * @param env_list is the input list
 *
 * @return boolean
 */
@NonCPS
def search_in_list(String element, List env_list)
{
    for (env_elem in env_list)
    {
        if (element == env_elem)
        {
            return true
        }
    } /* for */
    return false
}

/** **********************************************************************************************************
 * Return all Jenkins Agents of a label expression
 * - A label expression can be for example "Linux" or "Docker && Linux"
 * - IMPORTANT: Valid expression operators are only (expr) and (expr&&expr), nothing else.
 * - Details: https://jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#code-node-code-allocate-node
 * - we access the jenkins model api, so we have to mark it as NonCPS
 *  *
 * @param labelexpr specifies label
 */
@NonCPS
def getAllAgentsForALabel(String labelexpr) {
    def result = []
    def requestedLabelExprression = []
    labelexpr.split('&&').each { requestedLabelExprression.add(it.trim()) }
    for (aSlave in hudson.model.Hudson.instance.slaves) {
        if (aSlave.getLabelString()) {
            toBeAdded = true
            for (label in requestedLabelExprression) {
                if (!aSlave.getLabelString().split().contains(label)) {
                    toBeAdded = false
                    break
                }
            }
            if (toBeAdded) {
                result.add(aSlave.name)
            }
        }
    }
    return result
}


/** **********************************************************************************************************
 * Abort build if current WORKSPACE contains "@"
 *
 */
def AbortIfWorkspaceContainsAt() {
    if (WORKSPACE.contains('@')) {
        manager.addWarningBadge("Workspace contains @")
        error("Workspace contains @ ..exiting..")
    }
}

/** **********************************************************************************************************
 * - can be executed in "parallel" step
 * - calculates build name version
 *
 * @param release specifies name of release
* @param jobType specifies name of jobType
 */
def CalculateBuildVersion(String release, String jobType) {
    if (jobType == "getflash") {
        buildName = sh(returnStdout: true, script: "echo \"`basename ${env.GERRIT_PROJECT}`_${env.GERRIT_CHANGE_NUMBER}_${env.GERRIT_PATCHSET_NUMBER}\"").trim()
    } else {
        buildName = sh(returnStdout: true, script: "echo \"${release}_`date +%G%V%2w_%H%M`_${env.BUILD_NUMBER}\"").trim()
    }
    return buildName

}

/** **********************************************************************************************************
 * Detects the state of a defined (parallel) stage of the current job
 *
 * @param stageDisplayName: displayName of the stage you would like get the state of
 *
 * @return state of the stage provided as "stageDisplayName" (will be UNDEFINED if stage is not found)
 */
@NonCPS
def getStageState(String stageDisplayName) {
  WorkflowRun run = Jenkins.instance.getItemByFullName(JOB_NAME).getBuild(BUILD_NUMBER)
  PipelineNodeGraphVisitor visitor = new PipelineNodeGraphVisitor(run)
  def stage = visitor.getPipelineNodes().find{ it.displayName ==~ /${stageDisplayName}/ }
  if (stage != null) {
    return stage.getStatus().getState().toString()
  } else {
    return "UNDEFINED"
  }
}

/**
 * Jenkins Queue Operations
 */

/**
 * Clear the Jenkins queue of only the current job. This allows jobs to execute only
 * one build of a latest master branch when there is more than one job queued.
 * Builds queued for other jobs are not touched.
 */
def clearJobQueue() {
  def q = jenkins.model.Jenkins.getInstance().getQueue()
  def items = q.getItems()

  for (item in items) {
    // Strip the current build number from this job's URL to compare against queued build URLs
    rawBaseUrl = currentBuild.rawBuild.getUrl().replaceAll("/${currentBuild.rawBuild.getId()}\\/\$", '/')

    if(item.task.getUrl() == rawBaseUrl) {
      q.cancel(item)
    }
  }
}

/**
 *
 * @param jobname: job name without /job/
 * @param buildnumber: build number from jenkins
 * @return state of triggered job
*/
String getArtifactsDir(String jobname, String buildnumber) {
    List artifacturlList =  ["/var/jenkins_home"] + jobname.replace('//','/').tokenize('/')
    String artifacturl =  artifacturlList.join('/jobs/')
    return "${artifacturl}/builds/${buildnumber}/archive"
}

/** To Replace existing build() from jenkins.
 * Key feature:
 * eb_build(job: triggerjob, wait: true/false, propagate: true/false, parameters: build_params), but downstream job will not abort if upstream is aborted.
 * Return Map:
 *  - BUILD_URL: build url of downstream job
 *  - BUILD_NUMBER: build number of downstream job
 *  - RESULT: result of downstream job
 *  - EXCEPT: If any exception was catched in downstream job.
 *  - ArtifactDir: Directory to artifactory archive folder for downstream job in jenkins master.
 * @param job: job name without /job/
 * @param wait: true/false
 * @param propagate: true/false
 * @param parameters: list of build params
 * @return return a map for build info
**/
Map eb_build(Map args){
    List __job =  [JENKINS_URL] + args.job.replace('//','/').tokenize('/')
    String job =  __job.join('/job/') // job need to be full path from top folder until the job
    String jobPath = args.job.toString()
    String token = args.job.replace('//','/').tokenize('/')[-1]
    List __parameters = ["token="+ token] + args.parameters
    String parameters = __parameters.join('&')
    Integer time_out = (args.timeout == null) ? 1800 : args.timeout.toInteger() //default 30 minutes
    boolean wait = (args.wait == null) ? true : args.wait
    boolean propagate = (args.propagate == null) ? true : args.propagate
    Map retMap = [:]
    retMap.RESULT = "SUCCESS"
    print("Scheduling project:" + job)
    println("Passed Arguments: " + args)
    //TODO:: Convert all groovy files so they send the parameters list properly instead of this workaround
    def buildParams = args.parameters.collect { param ->
        def parts = param.split('=', 2)
        string(name: parts[0], value: parts.length > 1 ? parts[1] : "")
    }
    println("parametersList:" + buildParams)
    println("Scheduling project: " + hudson.console.ModelHyperlinkNote.encodeTo(job,args.job.replace("/"," » ")))
    def triggered_job_url
    try {
    // Set a timeout for the job triggering step
        timeout(time: time_out, unit: 'SECONDS') {
            def buildInfo = build job: jobPath, parameters: buildParams, wait: wait, propagate: false
            // If buildInfo is not null, then the job was triggered successfully
            if (buildInfo) {
                triggered_job_url = buildInfo.absoluteUrl
                retMap.BUILD_URL = triggered_job_url
                retMap.BUILD_NUMBER = buildInfo.number
                retMap.RESULT = buildInfo.getResult()
                println("Starting building: " + hudson.console.ModelHyperlinkNote.encodeTo(triggered_job_url,args.job.replace("/"," » ") + " #" + retMap.BUILD_NUMBER))
                println("Result: " + hudson.console.ModelHyperlinkNote.encodeTo(triggered_job_url,args.job.replace("/"," » ") + " #" + retMap.BUILD_NUMBER) + ": ${retMap.RESULT}")
            } else {
                echo "Job could not be triggered or job object is null."
                retMap.RESULT = "FAILURE"
            }
            retMap.RESULT = (retMap.RESULT != "SUCCESS" && retMap.RESULT != "ABORTED" ) ? "FAILURE" : retMap.RESULT
            if (propagate == true) {
                if (args.configRuntime?.stage_results) {
                    // set stage result in configRuntime
                    args.configRuntime.stage_results[STAGE_NAME] = retMap.RESULT
                } else {
                    println "Can not change result of stage '${STAGE_NAME}' to '${retMap.RESULT}' in configRuntime " +
                            'because configRuntime was not given as argument! The stage results printed at the end of the ' +
                            'pipeline may not be correct!'
                }
                currentBuild.result = retMap.RESULT
                if (retMap.RESULT != "SUCCESS") {
                    retMap.EXCEPT = new hudson.AbortException("Failure in Downstream job » ${triggered_job_url}console")
                }
            }
            // get ArtifactsDIr function is not being found due to some groovy issues.
            List artifacturlList =  ["/var/lib/jenkins"] + jobPath.replace('//','/').tokenize('/')
            String artifacturl =  artifacturlList.join('/jobs/')
            retMap.ArtifactDir = "${artifacturl}/builds/${retMap.BUILD_NUMBER}/archive"
        }
    } catch (Exception err) {
        echo 'Exception occurred: ' + err.toString()
        err.printStackTrace()
        echo "The downstream job appears to be disabled/internal failures."
        throw err
    }
    return retMap
}

/**
 * Get parent job's build number, from where we replayed or Rebuild by using ,,Rebuild Plugin``
 * @return result of execution
*/
def get_parent_job_number(){
    if (currentBuild.rawBuild.getCause(org.jenkinsci.plugins.workflow.cps.replay.ReplayCause) != null) {
        return currentBuild.rawBuild.getCause(org.jenkinsci.plugins.workflow.cps.replay.ReplayCause).getOriginalNumber().toString()
    } else {
        def build_url = BUILD_URL + 'api/json?pretty=true'
        result = sh(returnStdout:true, script:"""#!/bin/bash -e
            curl -n -s ${build_url} | jq -jr '.actions[]? | .causes[]? | select(._class == "com.sonyericsson.rebuild.RebuildCause") | .upstreamBuild'
        """).trim()
        return result
    }
}

def isJiraTicket(String gerritCommitMsg) {
    String vmsgtemp = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new CommonEnvironment(this).loadGlobalBashLibs()} && is_jira_ticket ${gerritCommitMsg}""").trim()
    return vmsgtemp
}

def ebEmail(String subject, String sender, String recipients, String mailBody, String attachmentList) {
    sh("""#!/bin/bash -e
       source ${new CommonEnvironment(this).loadGlobalBashLibs()} && eb_mail --subject \"${subject}\" --from ${sender} --to ${recipients}
       --body \"${mailBody}\" --attachments \"${attachmentList}\"""")
}
def updateSwlVersion(String file, String repo) {
    Date date = new Date()

    // Get Year, weeknumber, weekday, counter details from swl xml file
    def v_content = sh(returnStdout: true, script: "grep \'<VERSION>\' ${file}").trim()
    def (_,vyy,vwn,vwd,vc) = ( v_content =~ /^<VERSION>(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})<\/VERSION>$/)[0]

    // Get today's details using date class
    def w = (date.getAt(Calendar.WEEK_OF_YEAR) - 1 == 0) ? 1 : (date.getAt(Calendar.WEEK_OF_YEAR) - 1)
    def wn = (w < 10) ? "0"+w.toString() : w.toString()
    def dn = "0"+(date.getAt(Calendar.DAY_OF_WEEK) - 1).toString()
    def yr = date.format("yy").toString()

    //Check if there is another build today so that we will increase the counter.
    if ( vyy.toInteger()==yr.toInteger() && vwn.toInteger() == wn.toInteger() && vwd.toInteger() == dn.toInteger() ){
        vc = vc.toInteger()+1
    }else{
        //reset the counter for the firstbuild of the day
        vc=1
    }
    vc = (vc < 10) ? "0"+vc.toString() : vc.toString()
    new_version = yr+'.'+wn+'.'+dn+'.'+vc
    // Used shall sed command to replace the version.
    sh(returnStdout: true, script: """#!/bin/bash -x
    sed -Eri \"s|<VERSION>(.*)</VERSION>|<VERSION>${new_version}</VERSION>|\" ${file}""").trim()
    sh(returnStdout:true, script:"""#!/bin/bash -x
    git --git-dir=${repo}/.git --work-tree=${repo} commit -a -m 'SWL version change by snapshot' -m 'Tracing-id: T2KB1-13018'
      """).trim()
}