package com.eb.lib.aosp

import com.eb.lib.aosp.PipelineEnvironment
import hudson.model.Job
import hudson.model.ParametersAction
import jenkins.model.Jenkins
import org.yaml.snakeyaml.Yaml

@NonCPS
def printConfigRuntimeYaml(configRuntime) {
    Yaml yaml = new Yaml()
    echo('----- configRuntime ---------')
    echo yaml.dump(configRuntime)
    echo ('--- end of configRuntime ---')
}

@NonCPS
def __INFO(String msg){
    def max=100
    def a = "#"
    def barrierleng = (max-msg.length())/2-1
    barrierleng = (barrierleng < 0) ? 4 : barrierleng
    def barrier = a*barrierleng
    println(barrier + " " + msg + " " + barrier)
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

boolean isJobStartedByUserOrTimer(List<hudson.model.Cause> causes){
    boolean isStartedByUserOrTimer = false
    switch(causes.getAt(0).getClass()){
        case hudson.model.Cause$UserIdCause:
            isStartedByUserOrTimer = true
        break
        case hudson.triggers.TimerTrigger.TimerTriggerCause:
            isStartedByUserOrTimer = true
        break
        case com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritCause:
            isStartedByUserOrTimer = false
        break
        case hudson.model.Cause$UpstreamCause:
            isStartedByUserOrTimer = !(env.GERRIT_CHANGE_OWNER_EMAIL || env.GERRIT_PATCHSET_UPLOADER_EMAIL)
        break
        default:
            isStartedByUserOrTimer = false
        break
    }
    return isStartedByUserOrTimer
}

// Assign actual stack target label
@NonCPS
String replaceStackTargetLabel(String string, String stackTargetLabel){
    def stackTargetLabelPattern='<STACK_TARGET_LABEL>'
    return string.replace(stackTargetLabelPattern,stackTargetLabel)
}

// Node has label?
Boolean nodeHasLabel(String buildnode, String label) {
  return Jenkins.getInstanceOrNull().getNode(buildnode).labelString.contains(label)
}

// Node online?
Boolean isNodeOnline(String buildnode) {
  return Jenkins.getInstanceOrNull().getNode(buildnode).toComputer().isOnline()

}

// Get the least loaded node in supported nodes for job
String getLeastLoadedNode(List buildnodelist) {
    def availableExecutors = 0
    def bestNode = "_na_"
    if (buildnodelist.size() == 1) {
        def buildnode = buildnodelist[0]
        if (!isNodeOnline(buildnode)) {
          echo "WARNING: Node " + buildnode + " is offline. Can't use it."
          return bestNode
        }
        if (nodeHasLabel(buildnode, "RESERVED")) { // If node label is RESERVED, don't use it.
          echo "WARNING: Can't use RESERVED node " + buildnode + ". Remove RESERVED label from manage nodes page if you want."
          return bestNode
        }
        return buildnode
    } else {
        buildnodelist.each { buildnode ->
            echo "Checking node: " + buildnode
            if (!isNodeOnline(buildnode)) {
              echo "WARNING: Node " + buildnode + " is offline. Can't use it."
              return
            }

            hudson.model.Computer node_c = Jenkins.getInstanceOrNull().getComputer(buildnode)
            if (nodeHasLabel(buildnode, "RESERVED")) {
              echo "WARNING: Can't use RESERVED node " + buildnode + ". Remove RESERVED label from manage nodes page if you want."
              return
            }
            //When x number of jobs initiate the Verify pipeline simultaneously, they all receive the same build node.
            def random = new Random().nextInt(10 - 2 + 1) + 2
            sleep(time:random, unit:"SECONDS")
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
    def List bestnodelist = []

    buildnodelist.each { buildnode ->
        if (!isNodeOnline(buildnode)) {
          echo "WARNING: Node " + buildnode + " is offline. Can't use it."
          return
        }
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

// Set given label to a node
String setNodeLabel(String buildnode, String labelString) {
  hudson.model.Node node = Jenkins.getInstanceOrNull().getNode(buildnode)
  def label = node.labelString + " " + labelString
  node.setLabelString(label)
  node.save()
}

// Remove label from a node
String removeNodeLabel(String buildnode, String labelString) {
  hudson.model.Node node = Jenkins.getInstanceOrNull().getNode(buildnode)
  def label = node.labelString.replaceAll(labelString, "")
  node.setLabelString(label)
  node.save()
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
// If propagate is true, then configRuntime: configRuntime is required to set the stage result properly
// Return Map:
//  *- BUILD_URL: build url of downstream job
//  *- BUILD_NUMBER: build number of downstream job
//  *- RESULT: result of downstream job
//  *- EXCEPT: If any exception was catched in downstream job.
//  *- ArtifactDir: Directory to artifactory archive folder for downstream job in jenkins master.
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

def gerrit_SetReview(String gerrit_host, String gerrit_change_number, String gerrit_patchset_number, String message, Number review_score) {
    if(gerrit_change_number && gerrit_patchset_number) {
        sh """ssh -p 29418 ${gerrit_host} \
        gerrit review ${gerrit_change_number},${gerrit_patchset_number} \
        -m '\"${message}\"' --verified ${review_score}"""
    }
}

def gerrit_PostComment(String gerrit_host, String gerrit_change_number, String gerrit_patchset_number, String message) {
    if(gerrit_change_number && gerrit_patchset_number) {
        sh """ssh -p 29418 ${gerrit_host} \
        gerrit review ${gerrit_change_number},${gerrit_patchset_number} \
        -m '\"${message}\"'"""
    }
}


def configureWorkspace(String worker_type, List params) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                onHandlingException("${worker_type} ${SOURCE_VOLUME}") {
                    sh """#!/bin/bash -e
                    source ${new PipelineEnvironment(this).loadBashLibs()} && aosp_ws_worker ${VERBOSE} ${worker_type} ${PIPELINE_TYPE} ${SOURCE_VOLUME} ${SOURCE_VOLUME_BASELINE} ${RETAIN_WORKSPACE}"""
                }
            }
        }
    }
}

def updateDevelXml(String worker_type, List params) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                onHandlingException("${worker_type} ${SOURCE_VOLUME}") {
                    sh(returnStdout:true, script:"""#!/bin/bash -e
                    #create WS
                    source ${new PipelineEnvironment(this).loadBashLibs()} && aosp_ws_worker ${VERBOSE} ${worker_type} ${PIPELINE_TYPE} ${SOURCE_VOLUME} ${SOURCE_VOLUME_BASELINE} ${RETAIN_WORKSPACE}
                    #repo sync
                    aosp_ws_reposync ${VERBOSE} ${PIPELINE_TYPE} ${SOURCE_VOLUME} \
                    ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${REPO_MANIFEST_XML} ${FRESH_REPO_WORKSPACE}""")

                    // Update target.xml
                    sh(returnStdout:true, script:"""#!/bin/bash -ex
                    pushd \${SOURCE_VOLUME} &> /dev/null
                    [[ -d .repo/manifests/tmp_aosp_release ]] && rm -rf .repo/manifests/tmp_aosp_release
                    git clone -b \${REPO_RELEASE_MANIFEST_REVISION} \${REPO_RELEASE_MANIFEST_URL} .repo/manifests/tmp_aosp_release &> /dev/null

                    [[ -f .repo/manifests/head_devel.xml ]] && rm -rf .repo/manifests/head_devel.xml
                    repo manifest -r -o .repo/manifests/head_devel.xml

                    cp .repo/manifests/head_devel.xml .repo/manifests/tmp_aosp_release/\${REPO_MANIFEST_XML}
                    popd &> /dev/null
                    pushd \${SOURCE_VOLUME}/.repo/manifests/tmp_aosp_release &> /dev/null
                    if [[ "x\$(git diff)" != "x" ]]; then
                        git  add \${REPO_MANIFEST_XML}
                        git commit -m "${env.USER} Devel job \${PROJECT_RELEASE_VERSION} - updateDevelxml : \${REPO_MANIFEST_XML}"
                        git push \$(git remote) HEAD:refs/heads/\${REPO_RELEASE_MANIFEST_REVISION}
                    fi
                    popd &> /dev/null
                    """)
                }
            }
        }
    }
}

def configureSync(List params) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                onHandlingException("Sync WS For ${SOURCE_VOLUME}") {
                    try { DEV_NULL } catch (err) { DEV_NULL = "${new PipelineEnvironment(this).loadBashLibs()}" }
                    if (PIPELINE_TYPE.toLowerCase().contains("verify")) {
                        try { DEPENDENCIES } catch (err) { DEPENDENCIES = "" }
                        sh(returnStdout:true, script:"""#!/bin/bash -e
                        source ${new PipelineEnvironment(this).loadBashLibs()} && source ${DEV_NULL} && aosp_ws_reposync ${VERBOSE} ${PIPELINE_TYPE} ${SOURCE_VOLUME} \
                        ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${REPO_MANIFEST_XML} ${FRESH_REPO_WORKSPACE} \
                        ${GERRIT_HOST} ${GERRIT_PROJECT} ${GERRIT_CHANGE_NUMBER} \
                        ${GERRIT_PATCHSET_NUMBER} \"${DEPENDENCIES}\" """)
                    } else if (PIPELINE_TYPE.toLowerCase().contains("devel")) {
                        sh(returnStdout:true, script:"""#!/bin/bash -e
                        source ${new PipelineEnvironment(this).loadBashLibs()} && source ${DEV_NULL} && aosp_ws_reposync ${VERBOSE} ${PIPELINE_TYPE} ${SOURCE_VOLUME} \
                        ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${REPO_MANIFEST_XML} ${FRESH_REPO_WORKSPACE} """)
                    } else {
                        sh(returnStdout:true, script:"""#!/bin/bash -e
                        source ${new PipelineEnvironment(this).loadBashLibs()} && source ${DEV_NULL} && aosp_ws_reposync ${VERBOSE} ${PIPELINE_TYPE} ${SOURCE_VOLUME} \
                        ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${REPO_MANIFEST_XML} ${FRESH_REPO_WORKSPACE}""")
                    }
                }
            }
        }
    }
}


def configureChangeLog(List params) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                dir "${SOURCE_VOLUME}", {
                    onHandlingException("Release Change Log: ${SOURCE_VOLUME}") {
                       /**
                        Generate change list for build here, need to be done before pushing release happens.
                        **/
                        Date date = new Date()
                        Random rnd = new Random()
                        def baseline_check_tmp = "/tmp/baseline_check_${TARGET_ID}_" + date.format("yyyy-MM-dd_HH-mm-ss") + "_" + rnd.next(12)
                        sh(returnStdout:true, script:"""#!/bin/bash -ex
                            rm -rf ${TARGET_ID}.xml release_note_*.log ${env.WORKSPACE}/${TARGET_ID}.xml ${env.WORKSPACE}/release_note_*.log
                            repo manifest -o ${TARGET_ID}.xml -r
                            [[ -f ${env.WORKSPACE} ]] && rm -rf ${env.WORKSPACE}
                            [[ ! -d ${env.WORKSPACE} ]] && mkdir ${env.WORKSPACE}
                            cp -rf ${TARGET_ID}.xml ${env.WORKSPACE}/${TARGET_ID}.xml
                            git clone -b ${REPO_MANIFEST_LOG_BASE} ${REPO_MANIFEST_RELEASE} ${baseline_check_tmp}
                        """)
                        def return_code = "0"
                        def diff_manifests = ""
                        def changelog= ""
                        def rnote_type
                        def prev_baseline=''
                        CHANGE_LOG_TYPES.tokenize(' ').each{ rntype ->
                            rnote_type = "${rntype}_${PROJECT_BRANCH}".toUpperCase()
                            String allTags=runShell("git --git-dir=${baseline_check_tmp}/.git --work-tree=${baseline_check_tmp} log --oneline --decorate=short --pretty=%d | \
                                        grep \"tag:\\s${rnote_type}\" | grep \"tag:\\s${TAG_PREFIX}\" | head -n1 2>/dev/null", false)
                            allTags?.replaceAll("[()]","").tokenize(',').each{ tg ->
                                if (tg.contains('tag: ' + TAG_PREFIX) && (prev_baseline == '')){
                                    prev_baseline = tg.tokenize(':')[-1].trim()
                                }
                            }

                            if ((prev_baseline == "null") || (prev_baseline == "")) {
                                changelog = "(*** ${PROJECT_RELEASE_VERSION} is the first baseline ***)"
                            }
                            else {
                                changelog = "(*** CHANGES FROM ${prev_baseline}(last ${rntype}) TO ${PIPELINE_TYPE}:${PROJECT_RELEASE_VERSION} ***)\n"
                                println("\nChecking out manifest from baseline {} (last {})\n----\n".format(prev_baseline, rntype))
                                return_code = sh(returnStdout:true, script:"""#!/bin/bash -x
                                    git --git-dir=${baseline_check_tmp}/.git --work-tree=${baseline_check_tmp} checkout ${prev_baseline}
                                    echo \$?
                                """).trim()

                                if (return_code != "0"){
                                    changelog = changelog + "Can't find diff because git-tag ${prev_baseline} can't be checked out!"
                                }

                                repo_diff_manifests = "repo diffmanifests ${baseline_check_tmp}/${TARGET_ID}.xml ${TARGET_ID}.xml"
                                println(repo_diff_manifests)

                                diff_manifests = sh(returnStdout:true, script:"""#!/bin/bash -x
                                    repo diffmanifests ${baseline_check_tmp}/${TARGET_ID}.xml ${TARGET_ID}.xml
                                """)
                                if (diff_manifests != ""){
                                    changelog = changelog + diff_manifests
                                }
                                else {
                                    changelog = changelog + "No diff found."
                                }
                            }

                            def tmp_str_list = "${PROJECT_RELEASE_VERSION}".split("_")
                            def release_timestamp = "${tmp_str_list[-2]}_${tmp_str_list[-1]}"
                            def release_note_suffix = "AOSP_${release_timestamp}"
                            def release_note_file_name = "release_note_${rntype}_${release_note_suffix}.log"
                            println("-----------------------------------------------\n")
                            writeFile file: release_note_file_name, text: changelog
                            archiveArtifacts artifacts: release_note_file_name, allowEmptyArchive: true, fingerprint: true
                        }
                        sh("""
                            rm -rf ${baseline_check_tmp}
                          """)
                        archiveArtifacts artifacts: "${TARGET_ID}.xml", allowEmptyArchive: true, fingerprint: true

                    }
                }
            }
        }
    }
}

// Run aed2-robotests and aed2-integrationtests (Unit test targets from AOSP build)
def configureUnitTests(List params) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                docker.image("${DOCKER_IMAGE_ID}").inside("-e HOME=${env.HOME} -v /home:/home:rw -v /ssd:/ssd:rw -v /net:/net:rw -v /opt:/opt:rw -v ${SOURCE_VOLUME}:/aosp_workspace -v /ccache:/ccache:rw -v /etc/profile.d:/etc/profile.d:rw -v /tmp:/tmp:rw") {
                    onHandlingException("Build ${SOURCE_VOLUME}") {
                        try { USER_CUSTOM_BUILD_ENV } catch (err) { USER_CUSTOM_BUILD_ENV = "" }
                        try { DEV_NULL } catch (err) { DEV_NULL = "${new PipelineEnvironment(this).loadBashLibs()}" } // This workaround need to be replaced by perm solution
                          echo "Running Robotests using top level target from : https://asterix2-gerrit.ebgroup.elektrobit.com/c/elektrobit/device/aed2/+/2187"
                          echo "Running Integrationtests using top level target from : https://asterix2-gerrit.ebgroup.elektrobit.com/c/elektrobit/device/aed2/+/2413"
                          sh("""
                            rm -rf ${SOURCE_VOLUME}/out/target/product/*/obj/ROBOLECTRIC
                          """)
                          sh(returnStdout:true, script: """#!/bin/bash
                          source ${new PipelineEnvironment(this).loadBashLibs()} && source ${DEV_NULL} && aosp_build ${VERBOSE} /aosp_workspace ${LUNCH_TARGET} \"${MAKE_TARGET}\" ${OTA_GEN} ${OTA_CUSTOMIZED_METHOD} ${OTA_SCRIPT} ${SWUP_GEN} ${SWUP_SCRIPT} ${CCACHE_ENABLED} ${CCACHE_EXEC} ${CCACHE_DIR} ${CCACHE_UMASK} ${CCACHE_MAX_SIZE} ${BUILD_NAME} ${USER_CUSTOM_BUILD_ENV}""")
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
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                docker.image("${DOCKER_IMAGE_ID}").inside("-e HOME=${env.HOME} -v /home:/home:rw -v /ssd:/ssd:rw -v /net:/net:rw -v /opt:/opt:rw -v ${SOURCE_VOLUME}:/aosp_workspace -v /ccache:/ccache:rw -v /etc/profile.d:/etc/profile.d:rw -v /tmp:/tmp:rw") {
                    onHandlingException("Build ${SOURCE_VOLUME}") {
                        try { USER_CUSTOM_BUILD_ENV } catch (err) { USER_CUSTOM_BUILD_ENV = "" }
                        try { DEV_NULL } catch (err) { DEV_NULL = "${new PipelineEnvironment(this).loadBashLibs()}" } // This workaround need to be replaced by perm solution

                        try {
                          if (RUN_SONAR_ANALYSIS == "true") {}
                        } catch (err) {
                          RUN_SONAR_ANALYSIS = "false"
                        }

                        if(RUN_SONAR_ANALYSIS == "true") {
                          echo "Running sonarqube build"
                          sh(returnStdout:true, script: """#!/bin/bash -x
                          source ${new PipelineEnvironment(this).loadBashLibs()} && source ${DEV_NULL} && aosp_sonar_build ${VERBOSE} /aosp_workspace ${LUNCH_TARGET} ${SONAR_SERVER} ${SONAR_SCANNER} ${SONAR_BUILD_WRAPPER} ${SONAR_PROJECTKEY_PREFIX} "n/a" \"n/a\" """)
                        } else {
                          echo "Running normal AOSP build"
                          def rebuild_vts = ""
                          if (BUILD_VTS == "true") {
                            rebuild_vts = sh(returnStdout:true, script: """#!/bin/bash
                            source ${new PipelineEnvironment(this).loadBashLibs()} && has_repo_changed_since_last_build ${VERBOSE} /aosp_workspace ${LAST_BUILD_MANIFEST} \"${VTS_REPOSITORIES}\" """).trim()
                            if (rebuild_vts.length() > 0) {
                              echo "Repos ${rebuild_vts} have changed since last snapshot. Must rebuild VTS package."
                              MAKE_TARGET = "${MAKE_TARGET} ${VTS_MAKE_TARGET}"
                            } else {
                              echo "No need to rebuild VTS package."
                            }
                          }

                          sh(returnStdout:true, script: """#!/bin/bash
                          source ${new PipelineEnvironment(this).loadBashLibs()} && source ${DEV_NULL} && aosp_build ${VERBOSE} /aosp_workspace ${LUNCH_TARGET} \"${MAKE_TARGET}\" ${OTA_GEN} ${OTA_CUSTOMIZED_METHOD} ${OTA_SCRIPT} ${SWUP_GEN} ${SWUP_SCRIPT} ${CCACHE_ENABLED} ${CCACHE_EXEC} ${CCACHE_DIR} ${CCACHE_UMASK} ${CCACHE_MAX_SIZE} ${BUILD_NAME} ${USER_CUSTOM_BUILD_ENV}""")

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
                if (RUN_SONAR_ANALYSIS == "false") {
                  onHandlingException("Creating Flashing Image: ${SOURCE_VOLUME}/out/mat-deploy") {
                      sh(returnStdout:true, script: """#!/bin/bash
                      source ${new PipelineEnvironment(this).loadBashLibs()} && aosp_create_flash_image ${VERBOSE} ${SOURCE_VOLUME} ${FLASHIMAGE_CUSTOMIZED_METHOD} ${FLASHIMAGE_SCRIPT}""")
                  }
                }
            }
        }
    }
}

def configureGetFlash(List params) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                onHandlingException("Release Get Flash Binary: ${SOURCE_VOLUME}") {
                    sh """#!/bin/bash -e
                    source ${new PipelineEnvironment(this).loadBashLibs()} && aosp_get_flash_release ${VERBOSE} ${SOURCE_VOLUME} ${NET_SHAREDRIVE} ${BUILD_TYPE} ${TARGET_ID} ${GET_FLASH_VERSION} ${FLASHIMAGE_CUSTOMIZED_METHOD} ${FLASHIMAGE_SCRIPT}"""
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
            // Build sync from least loaded node to other nodes sort and unique
            "${BUILD_NODE_LIST}".trim().tokenize(' ').sort().unique().each { buildnode ->
                if (!isNodeOnline(buildnode)) {
                    echo "WARNING: Node " + buildnode + " is offline. Can't use it."
                    return
                }
                if (buildnode.trim() && (buildnode.trim() != "${LEAST_LOADED_NODE}")){
                    BuildSyncMap['syncWS_in_'+buildnode] = {
                        echo "Running  syncWS for ${buildnode} from ${LEAST_LOADED_NODE} and node list is ${BUILD_NODE_LIST}"
                        node(buildnode.trim()) {
                            onHandlingException("Devel Sync From ${LEAST_LOADED_NODE} to ${buildnode} for ${SOURCE_VOLUME}: ${SYNC_TIMES}") {
                                sh """#!/bin/bash
                                source ${new PipelineEnvironment(this).loadBashLibs()} && aosp_devel_build_sync ${SOURCE_VOLUME} ${SOURCE_VOLUME_BASELINE} ${LEAST_LOADED_NODE} ${SYNC_TIMES} ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${REPO_MANIFEST_XML}"""
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
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                copyArtifacts(projectName: env.JOB_NAME, filter: "*.xml", selector: specific(env.BUILD_NUMBER),fingerprintArtifacts: true, target: "${SOURCE_VOLUME}/")
                onHandlingException("Release Binary: ${SOURCE_VOLUME}") {
                    sh """#!/bin/bash -e
                    source ${new PipelineEnvironment(this).loadBashLibs()} && aosp_baseline_release ${VERBOSE} ${SOURCE_VOLUME} ${NET_SHAREDRIVE} ${PIPELINE_TYPE} ${LINK_NAME} ${BUILD_TYPE} ${REPO_MANIFEST_XML} ${REPO_MANIFEST_RELEASE} ${REPO_MANIFEST_RELEASE_REVISION} ${env.WORKSPACE} ${PROJECT_RELEASE_VERSION} \"${FILES_TO_PUBLISH}\" ${PREBUILT_RELEASE_NAME} ${LUNCH_TARGET} ${RUN_PROGUARD_UPLOAD} ${OTA_PUBLISH} ${SWUP_PUBLISH}|| true"""
                }
            }
        }
    }
}

def configureSonarqubeBuild(List params, List job_params, String pipelineType, boolean wait_duration) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
              createBtrfsSnapshot("${SONAR_SRC_VOLUME}", "${SONAR_VOLUME}")
              sh """#!/bin/bash -e
                  hostname
                  rm -rf ${SONAR_VOLUME}/out
              """
              createBtrfsSnapshot("${SONAR_SRC_VOLUME}" + "/out", "${SONAR_VOLUME}" + "/out")
              eb_build( job: "${SONAR_JOB}", wait: false, propagate: false, parameters: job_params )
            }
        }
    }
}

def configureDownstreamBuild(List params, List job_params) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                createBtrfsSnapshot("${SRC_VOLUME}", "${TARGET_VOLUME}")
                sh """#!/bin/bash -e
                  hostname
                  rm -rf ${TARGET_VOLUME}/out
              """
                createBtrfsSnapshot("${SRC_VOLUME}" + "/out", "${TARGET_VOLUME}" + "/out")
                eb_build( job: "${DOWNSTREAM_JOB}", wait: false, propagate: false, parameters: job_params )
            }
        }
    }
}

def configureAospLibUpload(List params) {
    return {
        withEnv(params){
            echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
            node("${LEAST_LOADED_NODE}") {
                onHandlingException("Upload Libs: ${SRC_VOLUME}") {
                    sh """#!/bin/bash -e
                    source ${new PipelineEnvironment(this).loadBashLibs()} && aosp_Lib_Upload ${VERBOSE} ${SRC_VOLUME} ${REPO_MANIFEST_RELEASE_REVISION} ${REPO_MANIFEST_RELEASE} ${TARGET_ID} ${BRANCH_VERSION} ${DEV_ENV} ${SCRIPT_PATH} ${ARTIFACT_REPO} ${GROUP_ID} || true"""
                }
            }
        }
    }
}

def configureAppRelease(List params) {
    return {
        withEnv(params){
            node("${LEAST_LOADED_NODE}") {
                onHandlingException("Release Integration Apks: ${SOURCE_VOLUME}") {
                    sh """#!/bin/bash -e
                    source ${new PipelineEnvironment(this).loadBashLibs()} && app_release ${VERBOSE} ${SOURCE_VOLUME} ${NET_SHAREDRIVE} ${PIPELINE_TYPE} ${PROJECT_RELEASE_VERSION} \"${APP_FILES_TO_PUBLISH}\" || true"""
                }
            }
        }
    }
}

def configureNotifyIntegrationCompletionInGerrit(List params) {
  return {
    withEnv(params) {
      echo "Running ${STAGE_NAME} for ${BUILD_TARGET} on ${LEAST_LOADED_NODE}"
      node("${LEAST_LOADED_NODE}") {
        onHandlingException("Notify Integration Completion: ${SOURCE_VOLUME}") {
            sh """#!/bin/bash
              source ${new PipelineEnvironment(this).loadBashLibs()} && notify_integration_completion_in_gerrit ${VERBOSE} ${SOURCE_VOLUME} ${BUILD_NAME} ${TARGET_ID} ${GERRIT_HOST} ${PIPELINE_TYPE} ${REPO_MANIFEST_URL} ${REPO_MANIFEST_REVISION} ${NET_SHAREDRIVE} ${PROJECT_BRANCH}
            """
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
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_source_baseline_name ${projectBranch} ${pipelineType} ${sourceVolume}""").trim()
    return sourceVolumeBaseline
}


def getReviewersEmailFromChangeNumber(String gerritHost, String gerritChangenumber) {
    String emailAddresses = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_reviewers_email_from_change_number ${gerritHost} ${gerritChangenumber}""").trim()
    return emailAddresses
}

def gerritGetDependencies(String verbose, String gerritCommitMsg, String gerritHost) {
    String dependencies = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_dependencies ${verbose} ${gerritCommitMsg} ${gerritHost}""").trim()
    return dependencies
}

def getAffectedProjects(String verbose, String gerritHost, String gerritDependencies) {
    // hash map to store project name, refspec and file list
    projects = [:]
    projectsList = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_affected_projects ${verbose} ${gerritHost} \"${gerritDependencies}\" """).trim()

    // get refspe and file list for each project
    projectsList.tokenize(' ').each { project ->
        //project contains name:change_number now split it and get name and change_number
        def project_name = project.tokenize(':')[0]
        def change_number = project.tokenize(':')[1]
        def project_refspec = sh(returnStdout:true, script:"""#!/bin/bash -e
            source ${new PipelineEnvironment(this).loadBashLibs()} && get_current_ref_from_change_number ${gerritHost} ${change_number}""").trim()
        def project_file_list = sh(returnStdout:true, script:"""#!/bin/bash -e
            source ${new PipelineEnvironment(this).loadBashLibs()} && get_files_from_change_number ${gerritHost} ${change_number}""").trim()
        //Note:For relation chain we override the refspec and file list with topmost change
        // store projects[project]={'refspec':refspec,'files':files }
        projects[project_name] = ['refspec':project_refspec, 'files':project_file_list.split(' ')]
    }
    return projects
}
def gerritGetDependentChanges(String verbose, String gerritChangenumber, String gerritHost) {
    String dependencies = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_relation_chain ${gerritHost} ${gerritChangenumber}""").trim()
    return dependencies
}


def getGerritEventMsg(String gerritEventCommentText) {
    String evengMsg = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_gerrit_event_msg ${gerritEventCommentText}""").trim()
    return  evengMsg
}

def getBuildOnNodeFromGerritEventMsg(String gerritEventCommentText) {
    String node = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_gerrit_build_on_node_from_event_msg ${gerritEventCommentText}""").trim()
    return  node
}

def isCommitRebasable(String gerritHost, String gerritChangeNumber, String gerritProject, String gerritBranch) {
    String rebasable = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && is_commit_rebasable ${gerritHost} ${gerritChangeNumber} ${gerritProject} ${gerritBranch}""").trim()
    return rebasable
}

def isJiraTicket(String gerritCommitMsg) {
    String vmsgtemp = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && is_jira_ticket ${gerritCommitMsg}""").trim()
    return vmsgtemp
}

def getAffectedManifests(String verbose, String repoManifestUrl, String repoDevManifestRevision, String gerritProject, String gerritBranch, String supportedTargetIds) {
    String manifestsList = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_affected_manifests ${verbose} ${repoManifestUrl} ${repoDevManifestRevision} ${gerritProject} ${gerritBranch} \"${supportedTargetIds}\"""").trim()
    return manifestsList
}

def isDevelRevisionBuilt(String repoReleaseManifestUrl, String repoDevManifestRevision, String repoManifestXml, String gerritHost, String gerritProject, String gerritNewRev, String gerritRefName) {
    def isRevBuilt = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && is_devel_rev_built ${repoReleaseManifestUrl} ${repoDevManifestRevision} ${repoManifestXml} ${gerritHost} ${gerritProject} ${gerritNewRev} ${gerritRefName}""").trim()
    return isRevBuilt
}

def isDevelReleased(String verbose, String repoReleaseManifestUrl, String repoDevManifestRevision, String repoRelManifestRevision, String repoManifestXml) {
    def develReleased = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && is_devel_released ${verbose} ${repoReleaseManifestUrl} ${repoDevManifestRevision} ${repoRelManifestRevision} ${repoManifestXml}""").trim()
    return develReleased
}

def isDevelRequired(String verbose, String repoReleaseManifestUrl, String repoDevManifestRevision, String develTag, String repoManifestXml) {
    def develReleased = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && is_devel_required ${verbose} ${repoReleaseManifestUrl} ${repoDevManifestRevision} ${develTag} ${repoManifestXml}""").trim()
    return develReleased
}
def getBuildNodes(String stackTarget, String stacktargetFile, String verbose) {
    new PipelineEnvironment(this).copyStackTargetFile(stacktargetFile)
    def nodes = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && get_build_nodes ${verbose} ${stackTarget} ${stacktargetFile}""").trim()
    return nodes
}

def aospGetFlashVersion(String gerritProject, String gerritChangenumber, String gerritPatchsetNumber) {
    def getFlashVersion = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && aosp_get_flash_version ${gerritProject} ${gerritChangenumber} ${gerritPatchsetNumber}""").trim()
    return getFlashVersion
}

def fetchJenkinsLog(String buildJobUrl, String logfilePath) {
    sh("""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && fetch_jenkins_log ${buildJobUrl} ${logfilePath}""")
}

def ebEmail(String subject, String mailBody, String sender, String recipients, String attachmentList) {
    sh("""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && eb_mail --subject \"${subject}\" --body \"${mailBody}\" --from ${sender} --to \"${recipients}\" --attachments \"${attachmentList}\"""")
}

def kw_create_local_project(String kw_user, String kw_server, String kw_port, String kw_project, String verbose="no") {
    sh("""/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && kw_create_local_project
        ${kw_user} ${kw_server} ${kw_port} ${kw_project} ${verbose}""")
}

def kw_gen_buildspec(String kw_user, String buildspec, String kwinject_ignore, String lunch_target, String modules_to_analyze, String verbose="no") {
    sh("""/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && kw_gen_buildspec ${kw_user}
        ${buildspec} \"${kwinject_ignore}\" ${modules_to_analyze} ${verbose}""")
}

def kw_kwcheck_run(String kw_user, String buildspec, String verbose="no", String kwcheck_extra_args="") {
    def reports_file = sh("""/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && kw_kwcheck_run ${kw_user}
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
    sudo btrfs subvolume snapshot ${source} ${destination} || mkdir -p ${destination}
  """)
}

def validateSonarProperty(String verbose, String gerritHost, String gerritProject, String gerritRefSpec, String gerrit_change_number, String gerrit_change_commit_message) {
    def validate_result = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new PipelineEnvironment(this).loadBashLibs()} && validate_sonar_property ${verbose} ${gerritHost} ${gerritProject} ${gerritRefSpec} ${gerrit_change_number} ${gerrit_change_commit_message}""").trim()
    return validate_result
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

/**
 * Get queued builds of a job.
 *
 * Job name is expected in the same format as in the url (e.g. '/job/BRI/job/myJob/').
 * Leading and trailing '/' as well as the '/job/' between folders/etc can be omitted.
 * 'BRI/myJob' is the same as '/job/BRI/job/myJob/'
 *
 * @param jobName full name of the job
 * @return list of builds or empty list if no build of this job is in the queue
 */
def getBuildsInQueue(String jobName) {
    final jobUrlPattern = ~'(^/|job/|/$)'
    def cleanJobName = jobName.replaceAll(jobUrlPattern, '')

    return Jenkins.instance.queue.items.findAll {
        it.task.url.replaceAll(jobUrlPattern, '').equalsIgnoreCase(cleanJobName)
    }
}

/**
 * Find queued builds where the build parameters are a superset of searchParams.
 * This means, that the build parameters contain all search parameters.
 *
 * For example, findBuildsInQueueByParams(exampleJob, ['GERRIT_CHANGE_NUMBER': 42])
 * searches for queued builds of exampleJob where the parameter GERRIT_CHANGE_NUMBER
 * has the value 42.
 *
 * Job name is expected in the same format as in the url (e.g. '/job/BRI/job/myJob/').
 * Leading and trailing '/' as well as the '/job/' between folders/etc can be omitted.
 * 'BRI/myJob' is the same as '/job/BRI/job/myJob/'
 *
 * @param jobName full name of the job
 * @param searchParams build parameters to search
 * @return builds in queue that have all search parameters or empty list if there is
 *         no such build in queue
 */
def findBuildsInQueueByParams(String jobName, Map<String, String> searchParams) {
    return getBuildsInQueue(jobName).findAll {build ->
        def buildParams = parsePropertiesString(build.params)
        searchParams.every { searchName, searchValue ->
            (buildParams.getProperty(searchName) == searchValue)
        }
    }
}

/**
 * Get running/finished builds of a job.
 *
 * Job name is expected in the same format as in the url (e.g. '/job/BRI/job/myJob/').
 * Leading and trailing '/' as well as the '/job/' between folders/etc can be omitted.
 * 'BRI/myJob' is the same as '/job/BRI/job/myJob/'
 *
 * @param jobName full name of the job
 * @return list of builds/runs or null if job was not found
 */
def getBuilds(String jobName) {
    def job = Jenkins.get().getItemByFullName(jobName, Job.class)
    return job?.getNewBuilds()
}

/**
 * Find running/finished builds where the build parameters are a superset of searchParams.
 * This means, that the build parameters contain all search parameters.
 *
 * For example, findBuildsByParams(exampleJob, ['GERRIT_CHANGE_NUMBER': 42]) searches for
 * builds of exampleJob where the parameter GERRIT_CHANGE_NUMBER has the value 42.
 *
 * Job name is expected in the same format as in the url (e.g. '/job/BRI/job/myJob/').
 * Leading and trailing '/' as well as the '/job/' between folders/etc can be omitted.
 * 'BRI/myJob' is the same as '/job/BRI/job/myJob/'
 *
 * @param jobName full name of the job
 * @param searchParams build parameters to search
 * @return builds that have all search parameters or empty list if there is no such build
 *         running/finished or null if job could not be found
 */
def findBuildsByParams(String jobName, Map<String, String> searchParams) {
    return getBuilds(jobName)?.findAll { build ->
        def buildParams = build?.actions?.find { it instanceof ParametersAction }?.parameters
        searchParams.every {searchName, searchValue ->
            buildParams?.find { it.name == searchName}?.value?.toString()?.equals(searchValue)
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
  app_pipelinelib = load("${env.WORKSPACE}/.launchers/libtools/pipeline/app_pipelinelib.groovy")
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

/************************************************************************************************************
 * Run shell commands or whole script
 *
 * @param inCmd shell command to run
 *
 * @return stdOutput of all the commands
**/
def runShell(inCmd, errorException=true) {
    String stdOutput = ''
    cmdList = (inCmd instanceof List)?inCmd:[inCmd]
    cmdList.each { cmd ->
        try {
            println("[RUN] ${cmd}")
            stdOutput = stdOutput + "\n" +
                        sh(returnStdout:true, script:"""#!/bin/bash
set -e
bash <<-EOF
#!/bin/bash
set -e
${cmd}
EOF""").trim()
        } catch(err) {
            if (errorException) {
                currentBuild.result = (err.toString()==null)?'ABORTED':'FAILURE'
                throwRuntimeException("[ERROR] ${cmd} --- " + err.toString(), currentBuild.result)
            } else {
                println("[INFO] ${stdOutput}\n${err.toString()}")
                printStackTrace()
            }
        }
    }
    return stdOutput.trim()
}
return this
