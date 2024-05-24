package com.eb.lib
import com.eb.lib.jobUtils
import groovy.json.JsonSlurperClassic
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor


/** **********************************************************************************************************
 * Determine if Gerrit triggered the current build.
 *
 *
 * @return bool saying if current build is triggered by Gerrit or not
 */
def TriggeredByGerrit() {
    def isStartedByGerrit = currentBuild.rawBuild.getCause(com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritCause) != null
    return isStartedByGerrit
}


/** **********************************************************************************************************
 * Determine what triggered a build.
 *
 *
 * @return list of either an upstream job and build number url or user name and user url
 */
def getTriggerCause() {
    def upstreamCause = currentBuild.rawBuild.getCause(Cause$UpstreamCause)
    if (upstreamCause) {
        def upstreamBuild = Jenkins.instance
            .getItemByFullName(upstreamCause.properties.upstreamProject)
            .getLastBuild()
        cause = [upstreamBuild.fullDisplayName, upstreamBuild.absoluteUrl]
    } else {
        def userCause = currentBuild.rawBuild.getCause(Cause$UserIdCause)
        if (userCause) {
            cause = [userCause.getUserName(), "${env.HUDSON_URL}user/" + userCause.getUserId()]
        } else {
            if (currentBuild.rawBuild.causes.find { it ==~ /.*TimerTriggerCause.*/ }) {
                cause = ['timer', currentBuild.absoluteUrl]
            } else {
                cause = ['unknown', currentBuild.absoluteUrl]
            }
        }
    }
    return cause
}

/** **********************************************************************************************************
 * Determine if the current build was triggered by a Change event.
 * For Gerrit: Check if GERRIT_EVENT_TYPE is set to "patchset-created" or "comment-added"
 * For GitHub: Not implemented yet
 */
def isChangeTriggered() {
    def isChange = false
    if (TriggeredByGerrit()) {
        isChange = env.GERRIT_EVENT_TYPE == "patchset-created" || env.GERRIT_EVENT_TYPE == "comment-added"
    }
    return isChange
}


/** **********************************************************************************************************
 * Determine if the current build was triggered by a Merge event.
 * For Gerrit: Check if GERRIT_EVENT_TYPE is set to "ref-updated" or "change-merged"
 * For GitHub: Not implemented yet
 */
def isMergeTriggered() {
    def isMerge = false
    if (TriggeredByGerrit()) {
        isMerge = env.GERRIT_EVENT_TYPE == "ref-updated" || env.GERRIT_EVENT_TYPE == "change-merged"
    }
    return isMerge
}

/**
 * set review score back to gerrit with message
*/
@NonCPS
def gerrit_SetReview(String gerrit_change_number, String gerrit_patchset_number, String message, Integer review_score) {
    if(gerrit_change_number && gerrit_patchset_number) {
        sh """ssh -p 29418 ${gerrit_host} \
        gerrit review ${gerrit_change_number},${gerrit_patchset_number} \
        -m '\"${message}\"' --verified ${review_score}"""
    }
}

/**
 * post comment to gerrit.
*/
@NonCPS
def gerrit_PostComment(String gerrit_host, String gerrit_change_number, String gerrit_patchset_number, String message) {
    if(gerrit_change_number && gerrit_patchset_number) {
        sh """ssh -p 29418 ${gerrit_host} \
        gerrit review ${gerrit_change_number},${gerrit_patchset_number} \
        -m '\"${message}\"'"""
    }
}

def gerritGetDependencies(String gerrit_host, String gerritCommitMsg) {
    String dependencies = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new CommonEnvironment(this).loadGlobalBashLibs()} && get_dependencies ${gerrit_host} ${gerritCommitMsg}""").trim()
    if (dependencies?.trim()?.length() > 0){
        new jobUtils().__INFO("VALID DEPENDENCIES: ${dependencies}")
    } else {
        new jobUtils().__INFO("NO VALID DEPENDENCIES FOUND!!!")
    }
    return dependencies
}

def getReviewersEmailFromChangeNumber(String gerritHost, String gerritChangenumber) {
    String emailAddresses = sh(returnStdout:true, script:"""#!/bin/bash -e
        source ${new CommonEnvironment(this).loadGlobalBashLibs()} && get_reviewers_email_from_change_number ${gerritHost} ${gerritChangenumber}""").trim()
    return emailAddresses
}
