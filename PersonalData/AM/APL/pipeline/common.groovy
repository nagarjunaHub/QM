def __INFO(String msg){
    def max=100
    def a = "#"
    def barrierleng = (max-msg.length())/2-1
    barrierleng = (barrierleng < 0) ? 4 : barrierleng
    def barrier = a*barrierleng
    println(barrier + " " + msg + " " + barrier)
}

// Numerate base on android version using for definition of branch name
def androidMaps(String androiVer){
    def _maps =  [
            "8":    "0",
            "9":    "1",
            "10":   "2",
            "11":   "3",
            "12":   "4"
    ]
    return _maps[androiVer]
}

// To delete locked resources
@NonCPS
String delete_locks(){
    def manager = org.jenkins.plugins.lockableresources.LockableResourcesManager.get()
    def resources = manager.getResources().findAll{
        !it.locked
    }
    resources.each{
        manager.getResources().remove(it)
    }
    manager.save()
}

@NonCPS
def parseJsonToMap(String json) {
    final slurper = new groovy.json.JsonSlurperClassic()
    return new HashMap<>(slurper.parseText(json))
}

@NonCPS
boolean isJobStartedByUser(List<hudson.model.Cause> causes){
    boolean isStartedByUser = false
    switch(causes.getAt(0).getClass()){
        case hudson.model.Cause$UserIdCause:
            isStartedByUser = true
            break
        case com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritCause:
            isStartedByUser = false
            break
        case hudson.model.Cause$UpstreamCause:
            isStartedByUser = !(env.GERRIT_CHANGE_OWNER_EMAIL || env.GERRIT_PATCHSET_UPLOADER_EMAIL)
            break
        default:
            isStartedByUser = false
            break
    }
    return isStartedByUser
}

// Assign actual stack target label
@NonCPS
String replaceStackTargetLabel(String string, String stackTargetLabel){
    def stackTargetLabelPattern='<STACK_TARGET_LABEL>'
    return string.replace(stackTargetLabelPattern,stackTargetLabel)
}

// Get the least loaded node in supported nodes for job
String getLeastLoadedNode(List buildnodelist) {
    def availableExecutors = 0
    def bestNode = "_na_"
    if (buildnodelist.size() == 1) {
        return buildnodelist[0]
    } else {
        buildnodelist.each { buildnode ->
            hudson.model.Computer node_c = Jenkins.getInstanceOrNull().getComputer(buildnode)
            def countIdle = node_c.countExecutors() - node_c.countBusy()
            if (bestNode  == "_na_") {
                bestNode = buildnode
                availableExecutors = countIdle
            } else {
                if (countIdle > availableExecutors){
                    bestNode = buildnode
                    availableExecutors = countIdle
                }
            }
        }
        return bestNode
    }
}

// Collects a list of Node names from the current Jenkins instance
String getBestBuildNode(List buildnodelist, String source_volume_baseline, String pipelineType) {
    if (buildnodelist.size() == 1) {
        return buildnodelist[0]
    } else {
        def List bestnodelist = []

        buildnodelist.each { buildnode ->
            def fp = new hudson.FilePath(Jenkins.getInstanceOrNull().getComputer(buildnode).getChannel(), "${source_volume_baseline}")
            if (pipelineType.toLowerCase().contains("verify")){
                if (fp.exists()){ // Prioritize to build on the node has devel-baseline
                    bestnodelist.add(buildnode.trim())
                }
            } else {
                if (! fp.exists()){ // Prioritize to build on the node doesn't have *-baseline
                    bestnodelist.add(buildnode.trim())
                }
            }
        }

        if (bestnodelist.size() == 0){
            return getLeastLoadedNode(buildnodelist)
        } else {
            return getLeastLoadedNode(bestnodelist)
        }
    }
}

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
} /* search_in_list */

// This method shouldn't be annotated with @NonCPS - see https://wiki.jenkins.io/display/JENKINS/Pipeline+CPS+method+mismatches
def onHandlingException(desc, Closure body) {
    echo "${desc}"
    try {
        body.call()
    } catch (hudson.AbortException e) {
        def m = e.message =~ /(?i)script returned exit code (\d+)/
        if (m) {
            def exitcode = m.group(1).toInteger()
            if (exitcode == 143) {
                echo "Error: Patchset was aborted by a new execution."
            } else if (exitcode == 404) {
                new hudson.AbortException("${desc}: Not Found")
            } else if (exitcode == 444) {
                new hudson.AbortException("${desc}: Cannot Be Done")
            } else if (exitcode == 400) {
                new hudson.AbortException("${desc}: Not Supported")
            } else if (exitcode >= 128) {
                throw e;    //  killed because of abort, letting through
            } else {
                echo "Error: ${desc}: An error occured (${e}) marking build as FAILURE."
            }
            currentBuild.result = "FAILURE"
            throw e;
            // Skip non fatal errors to deliver Gerrit report with available info
        } else {
            throw e;    //  killed because of an unknown error
        }
    }
}

String getArtifactsDir(String jobname, String buildnumber) {
    List artifacturlList =  ["/var/lib/jenkins"] + jobname.replace('//','/').tokenize('/')
    String artifacturl =  artifacturlList.join('/jobs/')
    return "${artifacturl}/builds/${buildnumber}/archive"
}

// To Replace existing build() from jenkins.
// Key feature:
// Same as build(job: triggerjob, wait: true/false, propagate: true/false, parameters: build_params), but downstream job will not abort if upstream is aborted.
// Return Map:
//  *- BUILD_URL: build url of downstream job
//  *- BUILD_NUMBER: build number of downstream job
//  *- RESULT: result of downstream job
//  *- EXCEPT: If any exception was catched in downstream job.
//  *- ArtifactDir: Directory to artifactory archive folder for downstream job in jenkins master.
Map eb_build(Map args){
    List __job =  [JENKINS_URL] + args.job.replace('//','/').tokenize('/')
    String job =  __job.join('/job/') // job need to be full path from top folder until the job
    String token = args.job.replace('//','/').tokenize('/')[-1]
    List __parameters = ["token="+ token] + args.parameters
    String parameters = __parameters.join('&')
    Integer time_out = (args.timeout == null) ? 1800 : args.timeout //default 30 minutes
    boolean wait = (args.wait == null) ? true : args.wait
    boolean propagate = (args.propagate == null) ? true : args.propagate
    String trigger_cmd = [job,"buildWithParameters?${parameters}"].join('/')

    Map retMap = [:]
    retMap.RESULT = "SUCCESS"
    print("Scheduling project:" + job)
    println("Scheduling project: " + hudson.console.ModelHyperlinkNote.encodeTo(job,args.job.replace("/"," » ")))
    def triggered_job_url = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/jenkins/jenkins.lib && eb_trigger ${VERBOSE} \"${trigger_cmd}\" ${wait}""").trim()
    if (wait == true) {
        if (triggered_job_url != "") {
            retMap.BUILD_URL = triggered_job_url
            retMap.BUILD_NUMBER = triggered_job_url.tokenize('/')[-1]
            println("Starting building: " + hudson.console.ModelHyperlinkNote.encodeTo(triggered_job_url,args.job.replace("/"," » ") + " #" + retMap.BUILD_NUMBER))
            retMap.RESULT = sh(returnStdout:true, script:"""#!/bin/bash -e
                source ${WORKSPACE}/.launchers/libtools/jenkins/jenkins.lib && eb_query \"${triggered_job_url}\" ${time_out}""").trim()
            println("Result: " + hudson.console.ModelHyperlinkNote.encodeTo(triggered_job_url,args.job.replace("/"," » ") + " #" + retMap.BUILD_NUMBER) + ": ${retMap.RESULT}")
        } else {
            retMap.RESULT = "FAILURE"
        }

        retMap.RESULT = (retMap.RESULT != "SUCCESS" ) ? "FAILURE" : "SUCCESS"
        if (propagate == true) {
            currentBuild.result = retMap.RESULT
            if (retMap.RESULT != "SUCCESS") {
                retMap.EXCEPT = new hudson.AbortException("Failure in Downstream job » ${triggered_job_url}console")
            }
        }
        retMap.ArtifactDir = getArtifactsDir(args.job,retMap.BUILD_NUMBER)
    }
    return retMap
}

@NonCPS
def gerrit_SetReview(String gerrit_host, String gerrit_change_number, String gerrit_patchset_number, String message, Integer review_score) {
    if(gerrit_change_number && gerrit_patchset_number) {
        sh """ssh -p 29418 ${gerrit_host} \
        gerrit review ${gerrit_change_number},${gerrit_patchset_number} \
        -m '\"${message}\"' --verified ${review_score}"""
    }
}

@NonCPS
def gerrit_PostComment(String gerrit_host, String gerrit_change_number, String gerrit_patchset_number, String message) {
    if(gerrit_change_number && gerrit_patchset_number) {
        sh """ssh -p 29418 ${gerrit_host} \
        gerrit review ${gerrit_change_number},${gerrit_patchset_number} \
        -m '\"${message}\"'"""
    }
}

@NonCPS
def gerritGetDependentChanges(String verbose, String gerritChangenumber, String gerritHost) {
    def dependencies = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/common.lib && get_relation_chain ${gerritHost} ${gerritChangenumber}""")
    return dependencies.trim()
}

def configureWorkspace(String worker_type, List params) {
    return {
        withEnv(params){
            node("${LEAST_LOADED_NODE}") {
                dir "${WORKSPACE}/.launchers", {
                    git branch: "${BUILD_TOOL_BRANCH}",
                            url: "${BUILD_TOOL_URL}"

                    onHandlingException("${worker_type} ${SOURCE_VOLUME}") {
                        sh """#!/bin/bash -e
                        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && aosp_ws_worker ${VERBOSE} ${worker_type} ${PIPELINE_TYPE} ${SOURCE_VOLUME} ${SOURCE_VOLUME_BASELINE}"""
                    }
                }
            }
        }
    }
}

def configureSync(List params) {
    return {
        withEnv(params){
            node("${LEAST_LOADED_NODE}") {
                dir "${WORKSPACE}/.launchers", {
                    git branch: "${BUILD_TOOL_BRANCH}",
                            url: "${BUILD_TOOL_URL}"

                    onHandlingException("Sync WS For ${SOURCE_VOLUME}") {
                        try { DEV_ENV } catch (err) { DEV_ENV = "${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib" }
                        if (PIPELINE_TYPE.toLowerCase().contains("verify")) {
                            try { DEPENDENCIES } catch (err) { DEPENDENCIES = "" }
                            sh(returnStdout:true, script:"""#!/bin/bash -e
                            source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && source ${DEV_ENV} && aosp_ws_reposync ${VERBOSE} ${PIPELINE_TYPE} ${SOURCE_VOLUME} \
                            ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${REPO_MANIFEST_XML} \
                            ${GERRIT_HOST} ${GERRIT_PROJECT} ${GERRIT_CHANGE_NUMBER} \
                            ${GERRIT_PATCHSET_NUMBER} \"${DEPENDENCIES}\" """)
                        } else if (PIPELINE_TYPE.toLowerCase().contains("devel")) {
                            sh(returnStdout:true, script:"""#!/bin/bash -e
                            source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && source ${DEV_ENV} && aosp_ws_reposync ${VERBOSE} ${PIPELINE_TYPE} ${SOURCE_VOLUME} \
                            ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${REPO_MANIFEST_XML}""")
                        } else {
                            sh(returnStdout:true, script:"""#!/bin/bash -e
                            source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && source ${DEV_ENV} && aosp_ws_reposync ${VERBOSE} ${PIPELINE_TYPE} ${SOURCE_VOLUME} \
                            ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${REPO_MANIFEST_XML}""")
                        }
                    }
                }
            }
        }
    }
}

// Configure to build within this pipeline
def configureBuild(List params) {
    return {
        withEnv(params){
            node("${LEAST_LOADED_NODE}") {
                docker.image("${DOCKER_IMAGE_ID}").inside("-e HOME=/home/jenkins -v /home:/home:rw -v /ssd:/ssd:rw -v /net:/net:rw -v /opt:/opt:rw -v ${SOURCE_VOLUME}:/aosp_workspace -v /ccache:/ccache:rw -v /etc/profile.d:/etc/profile.d:rw -v /tmp:/tmp:rw") {
                    dir "${WORKSPACE}/.launchers", {
                        git branch: "${BUILD_TOOL_BRANCH}",
                                url: "${BUILD_TOOL_URL}"

                        onHandlingException("Build ${SOURCE_VOLUME}") {
                            try { USER_CUSTOM_BUILD_ENV } catch (err) { USER_CUSTOM_BUILD_ENV = "" }
                            try { DEV_ENV } catch (err) { DEV_ENV = "${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib" } // This workaround need to be replaced by perm solution

                            try {
                                if (RUN_SONAR_ANALYSIS == "true") {}
                            } catch (err) {
                                RUN_SONAR_ANALYSIS = "false"
                            }

                            if(RUN_SONAR_ANALYSIS == "true") {
                                echo "Running sonarqube build"
                                sh(returnStdout:true, script: """#!/bin/bash -x
                              source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && source ${DEV_ENV} && aosp_sonar_build ${VERBOSE} /aosp_workspace ${LUNCH_TARGET} ${SONAR_SERVER} ${SONAR_SCANNER} ${SONAR_BUILD_WRAPPER} ${SONAR_PROJECTKEY_PREFIX} "n/a" \"n/a\" """)
                            } else {
                                echo "Running normal AOSP build"
                                def rebuild_vts = ""
                                if (BUILD_VTS == "true") {
                                    rebuild_vts = sh(returnStdout:true, script: """#!/bin/bash
                                source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && has_repo_changed_since_last_build ${VERBOSE} /aosp_workspace ${LAST_BUILD_MANIFEST} \"${VTS_REPOSITORIES}\" """).trim()
                                    if (rebuild_vts.length() > 0) {
                                        echo "Repos ${rebuild_vts} have changed since last snapshot. Must rebuild VTS package."
                                        MAKE_TARGET = "${MAKE_TARGET} ${VTS_MAKE_TARGET}"
                                    } else {
                                        echo "No need to rebuild VTS package."
                                    }
                                }

                                sh(returnStdout:true, script: """#!/bin/bash
                              source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && source ${DEV_ENV} && aosp_build ${VERBOSE} /aosp_workspace ${LUNCH_TARGET} \"${MAKE_TARGET}\" ${USER_CUSTOM_BUILD_ENV}""")

                                if (rebuild_vts.length() == 0 && BUILD_VTS == "true" ) {
                                    // Then copy android-vts.zip from previous build to out/ directory.
                                    echo "Copying android-vts.zip from previous build ${LAST_BUILD}/android-vts.zip"
                                    sh(returnStdout: true, script:"""#!/bin/bash
                                  cd ${SOURCE_VOLUME}
                                  [[ -d out/host/linux-x86/vts ]] || mkdir out/host/linux-x86/vts
                                  cp ${LAST_BUILD}/android-vts.zip out/host/linux-x86/vts/android-vts.zip
                                """)
                                }
                            }
                        }
                    }
                }
                if (RUN_SONAR_ANALYSIS == "false") {
                    onHandlingException("Creating Flashing Image: ${SOURCE_VOLUME}/out/mat-deploy") {
                        sh(returnStdout:true, script: """#!/bin/bash
                      source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && aosp_create_flash_image ${VERBOSE} ${SOURCE_VOLUME}""")
                    }
                }
            }
        }
    }
}

def configureGetFlash(List params) {
    return {
        withEnv(params){
            node("${LEAST_LOADED_NODE}") {
                dir "${WORKSPACE}/.launchers", {
                    git branch: "${BUILD_TOOL_BRANCH}",
                            url: "${BUILD_TOOL_URL}"

                    onHandlingException("Release Get Flash Binary: ${SOURCE_VOLUME}") {
                        sh """#!/bin/bash -e
                        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && aosp_get_flash_release ${VERBOSE} ${SOURCE_VOLUME} ${NET_SHAREDRIVE} ${BUILD_TYPE} ${TARGET_ID} ${GET_FLASH_VERSION}"""
                    }
                }
            }
        }
    }
}


// To support multiple nodes for one pipeline. Devel out artifacts need to be synced.
def configureDevelBuildSync(List params) {
    return {
        withEnv(params){
            def BuildSyncMap = [:]
            "${BUILD_NODE_LIST}".trim().tokenize(' ').each { buildnode ->
                if (buildnode.trim() && (buildnode.trim() != "${LEAST_LOADED_NODE}")){
                    BuildSyncMap[buildnode] = {
                        node(buildnode.trim()) {
                            dir "${WORKSPACE}/.launchers", {
                                git branch: "${BUILD_TOOL_BRANCH}",
                                        url: "${BUILD_TOOL_URL}"

                                onHandlingException("Devel Sync From ${LEAST_LOADED_NODE} to ${buildnode}: ${SYNC_TIMES}") {
                                    sh """#!/bin/bash
                                    source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && aosp_devel_build_sync ${SOURCE_VOLUME} ${SOURCE_VOLUME_BASELINE} ${LEAST_LOADED_NODE} ${SYNC_TIMES}"""
                                }
                            }
                        }
                    }
                }
            }

            try {
                if(BuildSyncMap) {
                    if ("${SYNC_TIMES}".toLowerCase().contains("start")){
                        "${SYNC_TIMERS}".trim().tokenize(" ").each { TIMER ->
                            sleep(time:TIMER.toInteger(),unit:"MINUTES")
                            parallel BuildSyncMap
                        }
                    } else {
                        parallel BuildSyncMap
                    }
                }
            } catch (err) {
                echo err.toString()
            }
        }
    }
}


def configureRelease(List params) {
    return {
        withEnv(params){
            node("${LEAST_LOADED_NODE}") {
                dir "${WORKSPACE}/.launchers", {
                    git branch: "${BUILD_TOOL_BRANCH}",
                            url: "${BUILD_TOOL_URL}"

                    onHandlingException("Release Binary: ${SOURCE_VOLUME}") {
                        sh """#!/bin/bash -e
                        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && aosp_baseline_release ${VERBOSE} ${SOURCE_VOLUME} ${NET_SHAREDRIVE} ${PIPELINE_TYPE} ${BUILD_TYPE} ${TARGET_ID} ${REPO_MANIFEST_RELEASE} ${REPO_MANIFEST_RELEASE_REVISION} ${WORKSPACE} ${PROJECT_RELEASE_VERSION} \"${FILES_TO_PUBLISH}\" ${PREBUILT_RELEASE_NAME} ${LUNCH_TARGET} || true"""
                    }
                }
                archiveArtifacts artifacts: "release_note_*.log,*.xml"
                sh """#!/bin/bash -e
                    rm -rf ${WORKSPACE}/release_note_*.log ${WORKSPACE}/*.xml
                """
            }
        }
    }
}

def configureAppRelease(List params) {
    return {
        withEnv(params){
            node("${LEAST_LOADED_NODE}") {
                dir "${WORKSPACE}/.launchers", {
                    git branch: "${BUILD_TOOL_BRANCH}",
                            url: "${BUILD_TOOL_URL}"

                    onHandlingException("Release Integration Apks: ${SOURCE_VOLUME}") {
                        sh """#!/bin/bash -e
                        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && app_release ${VERBOSE} ${SOURCE_VOLUME} ${NET_SHAREDRIVE} ${PIPELINE_TYPE} ${PROJECT_RELEASE_VERSION} \"${APP_FILES_TO_PUBLISH}\" || true"""
                    }
                }
            }
        }
    }
}

def configureNotifyIntegrationCompletionInGerrit(List params) {
    return {
        withEnv(params) {
            node("${LEAST_LOADED_NODE}") {
                dir "${WORKSPACE}/.launchers", {
                    git branch: "${BUILD_TOOL_BRANCH}",
                            url: "${BUILD_TOOL_URL}"

                    onHandlingException("Notify Integration Completion: ${SOURCE_VOLUME}") {
                        sh """#!/bin/bash
                  source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && notify_integration_completion_in_gerrit ${VERBOSE} ${SOURCE_VOLUME} ${BUILD_NAME} ${TARGET_ID} ${GERRIT_HOST} ${PIPELINE_TYPE} ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION}
                """
                    }
                }
            }
        }
    }
}

def sendEmail(String subject, String body, String sendfrom, String sendto, String mimetype) {
    mail body: "${body}",
            charset: "UTF-8",
            mimeType: "${mimetype}",
            from: "${sendfrom}",
            subject: "${subject}",
            to: "${sendto}"
}

def getSourceBaselineName(String projectBranch, String pipelineType, String sourceVolume) {
    String sourceVolumeBaseline = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && get_source_baseline_name ${projectBranch} ${pipelineType} ${sourceVolume}""").trim()
    return sourceVolumeBaseline
}


def getReviewersEmailFromChangeNumber(String gerritHost, String gerritChangenumber) {
    String emailAddresses = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/common.lib && get_reviewers_email_from_change_number ${gerritHost} ${gerritChangenumber}""").trim()
    return emailAddresses
}

def gerritGetDependencies(String verbose, String gerritCommitMsg, String gerritHost) {
    String dependencies = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && get_dependencies ${verbose} ${gerritCommitMsg} ${gerritHost}""").trim()
    return dependencies
}

def getGerritEventMsg(String gerritEventCommentText) {
    String evengMsg = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/common.lib && get_gerrit_event_msg ${gerritEventCommentText}""").trim()
    return  evengMsg
}

def isCommitRebasable(String gerritHost, String gerritChangeNumber, String gerritProject, String gerritBranch) {
    String rebasable = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/common.lib && is_commit_rebasable ${gerritHost} ${gerritChangeNumber} ${gerritProject} ${gerritBranch}""").trim()
    return rebasable
}

def isJiraTicket(String gerritCommitMsg) {
    String vmsgtemp = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && is_jira_ticket ${gerritCommitMsg}""").trim()
    return vmsgtemp
}

def getProjectTargetIds(String verbose, String repoManifestUrl, String repoDevManifestRevision, String gerritProject, String gerritBranch, String supportedTargetIds) {
    String projectTargetId = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && get_project_targetids ${verbose} ${repoManifestUrl} ${repoDevManifestRevision} ${gerritProject} ${gerritBranch} \"${supportedTargetIds}\"""").trim()
    return projectTargetId
}

def isDevelRevisionBuilt(String repoReleaseManifestUrl, String repoDevManifestRevision, String gerritHost, String gerritProject, String gerritNewRev, String gerritRefName) {
    def isRevBuilt = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && is_devel_rev_built ${repoReleaseManifestUrl} ${repoDevManifestRevision} ${gerritHost} ${gerritProject} ${gerritNewRev} ${gerritRefName}""").trim()
    return isRevBuilt
}

def isDevelReleased(String verbose, String repoReleaseManifestUrl, String repoDevManifestRevision, String repoRelManifestRevision) {
    def develReleased = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && is_devel_released ${verbose} ${repoReleaseManifestUrl} ${repoDevManifestRevision} ${repoRelManifestRevision}""").trim()
    return develReleased
}

def getBuildNodes(String stackTarget, String stacktargetFile, String verbose) {
    def nodes = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && get_build_nodes ${verbose} ${stackTarget} ${stacktargetFile}""").trim()
    return nodes
}

def aospGetFlashVersion(String gerritProject, String gerritChangenumber, String gerritPatchsetNumber) {
    def getFlashVersion = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/pipeline/pipeline.lib && aosp_get_flash_version ${gerritProject} ${gerritChangenumber} ${gerritPatchsetNumber}""").trim()
    return getFlashVersion
}

def fetchJenkinsLog(String buildJobUrl, String logfilePath) {
    sh("""#!/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/jenkins/jenkins.lib && fetch_jenkins_log ${buildJobUrl} ${logfilePath}""")
}

def ebEmail(String subject, String sender, String recipients, String mailBody, String attachmentList) {
    sh("""#!/bin/bash -e
       source ${WORKSPACE}/.launchers/libtools/common.lib && eb_mail --subject \"${subject}\" --from ${sender} --to ${recipients}
       --body \"${mailBody}\" --attachments \"${attachmentList}\"""")
}

def kw_create_local_project(String kw_user, String kw_server, String kw_port, String kw_project, String verbose="no") {
    sh("""/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/common.lib && kw_create_local_project
        ${kw_user} ${kw_server} ${kw_port} ${kw_project} ${verbose}""")
}

def kw_gen_buildspec(String kw_user, String buildspec, String kwinject_ignore, String lunch_target, String modules_to_analyze, String verbose="no") {
    sh("""/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/common.lib && kw_gen_buildspec ${kw_user}
        ${buildspec} \"${kwinject_ignore}\" ${modules_to_analyze} ${verbose}""")
}

def kw_kwcheck_run(String kw_user, String buildspec, String verbose="no", String kwcheck_extra_args="") {
    def reports_file = sh("""/bin/bash -e
        source ${WORKSPACE}/.launchers/libtools/common.lib && kw_kwcheck_run ${kw_user}
        ${buildspec} ${verbose} \"$kwcheck_extra_args\"""")
    return reports_file
}

// Get parent job's build number, from where we replayed or Rebuild by using ,,Rebuild Plugin``
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

def createBtrfsSnapshot(String source, String destination) {
    sh("""#!/bin/bash -e
    sudo btrfs subvolume snapshot ${source} ${destination}
  """)
}

/** **********************************************************************************************************
 * Search all Jobs and enable buildDiscarder if not on
 *
 * @param daysToKeep - the number of days to keep builds
 * @param numToKeep - the number of builds to keep
 * @param maxDaysToKeep - the maximum days builds should be kept
 *
 * @returns jobsFound - map of Job found without buildDiscarder on and owners (who have changed the job history)
 */
def findAndEnableBuildDiscard(daysToKeep, numToKeep, maxDaysToKeep) {

    def jobsFound = [:]

    /* Find all jobs that do not have buildDiscarder on and enable according to input parameters */
    Jenkins.instance.getAllItems(Job.class).each { item ->
        def notifyJobOwner = false
        if (!item.buildDiscarder) {
            println "Discard Old Builds not on for ${item.fullName}.  Enabling with defaults..."
            item.buildDiscarder = new hudson.tasks.LogRotator(daysToKeep, numToKeep)
            item.save()
            notifyJobOwner = true
        } else {
            /* Check if current settings are over the default limits */
            item.buildDiscarder.with() { bd ->
                currentDaysToKeep = bd.daysToKeep ? bd.daysToKeep.toInteger() : -1
                currentNumToKeep = bd.numToKeep ? bd.numToKeep.toInteger() : -1
            }
            if ( currentDaysToKeep > maxDaysToKeep) {
                println "Current Days to keep builds for ${item.fullName} is too high. " +
                        "Reducing to ${maxDaysToKeep}..."
                currentDaysToKeep = maxDaysToKeep
                item.buildDiscarder = new hudson.tasks.LogRotator(currentDaysToKeep, currentNumToKeep)
                item.save()
                notifyJobOwner = true
            }
            if (numToKeep > 0 && currentNumToKeep > numToKeep) {
                println "Current Number of builds to keep for ${item.fullName} is too high. " +
                        "Reducing to ${numToKeep}..."
                item.buildDiscarder = new hudson.tasks.LogRotator(currentDaysToKeep, numToKeep)
                item.save()
                notifyJobOwner = true
            }
        }
        /* Generate list of owners to notify if necessary*/
        if (notifyJobOwner) {
            def jobOwners = getOwnersByConfigHistory(item.fullName)
            jobsFound[item.fullName] = jobOwners
        }
    }

    return jobsFound

}


/** **********************************************************************************************************
 * Search all Job config history and return list of users that modified the config
 *
 * @param jobName - the job name for which to search the config history
 * @param maxFiles - the maximum number of history files to search.  Default is 30.
 *
 * @returns list of users that modified the job config history or null if no config history
 */
def getOwnersByConfigHistory(jobName, maxFiles=30) {

    def jenkinsHome = System.getenv('JENKINS_HOME')

    /* Check Job config history location.  If no history found, return null */
    def configHistoryLocation = "${jenkinsHome}/config-history/jobs/" + jobName.replaceAll(/\//, "/jobs/")
    def folderJobHistory = new File(configHistoryLocation)
    if (folderJobHistory.exists()) {
        historyFiles = new FileNameFinder().getFileNames(configHistoryLocation, '**/history.xml')
    } else {
        return null
    }

    /* Only search the maxinum number of files set by maxFiles */
    if (historyFiles.size() > maxFiles) {
        historyFiles = historyFiles.takeRight(maxFiles)
    }
    def configUsers = []
    historyFiles.each {
        def xml = new XmlSlurper().parse(it)
        /* Only add a config user once and ignore the SYSTEM and "anonymous" users*/
        def configUser = xml['userId'].toString()
        if (configUser != 'SYSTEM' && configUser != 'anonymous' && !configUsers.contains(configUser)) {
            configUsers.add(configUser)
        }
    }

    return configUsers

}

/**
 * Callable to create Docker container
 *
 * @param dockerImageName      Name of Docker container to build
 * @param dockerImageFile      Name of Docker image file
 */
def CreateDocker(String dockerImageName, String dockerImageFile) {
    docker.build(dockerImageName,
            "-f ${dockerImageFile} --build-arg USER_ID=\$(id -u) --build-arg GROUP_ID=\$(id -g) --build-arg USER_NAME=\$(id -nu) --build-arg GROUP_NAME=\$(id -gn) .")
}


/**
 * Callable to delete unused images and containers older than 47 hours as well as dangling images and volumes
 * INFO: there is a docker command (system prune) which does exactly what is implemented below BUT
 * with no regard to time, so it just deletes everything. This caused trouble in the past (IIP-19527).
 * We cannot use docker system prune unless docker v17.06, which will introduce a time filter feature.
 */
def deleteUnusedDockers() {
    try {
        echo "deleteUnusedDockers: kill containers running for more than 47 hours..."
        sh """docker ps --no-trunc --format '{{.ID}} {{.RunningFor}}' | grep ' days\\|weeks\\|months\\|years' | awk '{print \$1}' | xargs --no-run-if-empty docker kill"""
        echo "deleteUnusedDockers: remove docker dangling images..."
        sh """docker images -f "dangling=true" -q | xargs --no-run-if-empty docker rmi -f"""
        echo "deleteUnusedDockers: remove docker dangling volumes..."
        sh """docker volume ls -f "dangling=true" -q | xargs --no-run-if-empty docker volume rm -f"""
        echo "deleteUnusedDockers: remove ALL containers older than 47 hours..."
        sh """docker ps --no-trunc --filter "status=exited"  --format '{{.ID}} {{.RunningFor}}' | grep ' days\\|weeks\\|months\\|years' | awk '{print \$1}' | xargs --no-run-if-empty docker rm -f"""
        echo "deleteUnusedDockers: remove ALL images older that 47 hours..."
        sh """docker images --no-trunc --format '{{.ID}} {{.CreatedSince}}' | grep ' days\\|weeks\\|months\\|years' | awk '{ print \$1 }' | xargs --no-run-if-empty docker rmi -f"""
    } catch (Exception e) {
        echo "deleteUnusedDockers: Warning: Some docker artifacts may not have been deleted."
    }
}



/** ***************************************************************************
 * Created by uidu2755 on 06.12.2018.
 * This function finds all pipeline steps containing and error. Afterwards,
 * the function checks if the error message or the consoleLog of this step
 * contains a typical error message (specified with variables patterns).
 * Every match is displayed at the homepage of the build.
 *  a
 * @param build_url specifies the the build which shall be analysed
 */
def checkForErrors(build_url){
    // TODO: Shall be configurable, e.g. loaded from a config file/object
    patterns = ['ERROR:',
                'error:',
                'fail',
                'Error:',
                'fatal',
                'FAILED:',
                'fatal: reference is not a tree:',
                'No licenses available',
                'Error response from daemon:']

    excludes = ['Sending interrupt signal to process',
                'UI received SIGTERM']

    // Analyse build
    e = parse(build_url, patterns, excludes)

    // Display resuilts
    e.each{ link, lines ->
        url = """<a href="${link.minus('wfapi/describe')}"><br>Failed Pipeline Step</a>"""
        manager.createSummary("warning.png")
                .appendText("${lines.replaceAll('\n', '<br>')} ${url}", false, false, false, "black")
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

/** **********************************************************************************************************
 * Create properties object from a string of properties
 * - created by uidp3279 on 12.06.2018.
 *
 * @param s should be like the contents of build.props or prop1=val1 prop2=val2, etc.
 *
 * @return Properties object with accessible attributes/values
 */
Properties parsePropertiesString(String s) {
    final Properties p = new Properties()
    p.load(new StringReader(s))
    return p
}



/** **********************************************************************************************************
 * Delete builds that are in the Jenkins build queue based on Gerrit Review ID (Change Number)
 * - created by uidp3279 on 12.06.2018.
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
 * Creates markdown file containing last n commits within a repo environment
 * Function requires a repoo environment
 *
 * @param gitlog:  Name of generated markdown file
 * @param commits: Number of commits per repo repostiory
 */
def archiveGitLog(gitlog = 'gitlog.md', past = '1500') {
    commits = [:]
    project = ""
    echo "Creating $gitlog .."

    history = sh(returnStdout: true, script: "repo forall -c \"env|grep -oP \'REPO_PATH=\\K.*\' && git --no-pager log HEAD -${past.toString()} --oneline\"").trim()

    history.split('\n').each{
        if (it =~ / /) {
            commits[it] = project
        }
        else {
            project = it
        }
    }

    writeFile file: gitlog, text: JsonOutput.prettyPrint(JsonOutput.toJson(commits))
}

//get failed job Url
@NonCPS
String getFailedJobConsoleUrl(hudson.AbortException e){
    def msg = e.getMessage()
    def folders = []
    def job, buildNumber = ""
    def foldersMatcher = (msg =~ /[a-zA-Z_0-9-]+\s{1}\u00BB{1}/)
    def jobMatcher = (msg =~ /\u00BB?\s?[a-zA-Z_0-9-]+\s{1}#/)
    def buildNumberMatcher = (msg =~ /#\d+/)
    if( foldersMatcher.size() <= 0 ||
            jobMatcher.size() != 1 ||
            buildNumberMatcher.size() != 1) {
        return "Failed job's url can not be composed, please look into jenkins pipeline log."
    }
    for(def iterator : foldersMatcher){folders << iterator - ' \u00BB'}
    job = jobMatcher[0].split(' ')[1]
    buildNumber = buildNumberMatcher[0].substring(1)
    def jobs = Jenkins.instance.getAllItems()
    def term = "${folders.join('/job')}/$job"
    def failedJobUrl
    for(item in jobs){
        if(term.contains('Apps-Testrunners')){
            term = term.split('/').toList()[0] + "/Apps-Testrunners/" + term.split('/').toList()[2]
        }
        if(item.getFullName() == term){
            failedJobUrl = item.getUrl()
            break
        }
    }
    return Jenkins.instance.getRootUrl().toString() + failedJobUrl + buildNumber + '/console'
}


def app_pipelinelib_bash_functions() {
    app_pipelinelib = load("${WORKSPACE}/.launchers/libtools/pipeline/app_pipelinelib.groovy")
    return app_pipelinelib.app_pipelinelib_bash_functions()

}


def generate_app_bash_functions(String NODE, String WORKSPACE, String BUILD_TOOL_BRANCH, String BUILD_TOOL_URL,
                                String app_pipelinelib_bash_functions ) {

    // Generate the bash functions file from the groovy string. This way, you could "replay" the pipeline, and
    // test your pipelines easily.

    node("${NODE}") {
        dir "${WORKSPACE}/.launchers", {
            git branch: "${BUILD_TOOL_BRANCH}",
                    url: "${BUILD_TOOL_URL}"

            // App pipeline lib
            writeFile file: "${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib", text: app_pipelinelib_bash_functions

        }
    }
}


return this;
