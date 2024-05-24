package com.eb.lib
import com.eb.lib.jobUtils
import groovy.json.JsonSlurperClassic
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor

/************************************************************************************************************
 * Lite Clone, single branch
**/
@NonCPS
def gitCloneLite(String gerritProject, String gerritBranch, String folder=''){
    new jobUtils().runShell("git clone --quiet --single-branch --branch ${gerritBranch} ${gerritProject} ${folder} &>/dev/null")
}

/************************************************************************************************************
 * Determine if the current build was triggered by Timer
**/
boolean isTimerTriggered(){
    if (currentBuild.rawBuild.causes.find { it ==~ /.*TimerTriggerCause.*/ }) {
        return true
    }
    return false
}

/************************************************************************************************************
 * Determine if the current build was triggered by User
**/
boolean isUserTriggered() {
    if (!triggeredByGerrit()){
        return currentBuild.rawBuild.getCause(Cause$UserIdCause)
    }
    return false   
}

/************************************************************************************************************
 * Determine if the current build was triggered by a Change event.
 * For Gerrit: Check if GERRIT_EVENT_TYPE is set to "patchset-created" or "comment-added"
**/
Boolean isChangeTriggered() {
    if (triggeredByGerrit()) {
        return (env.GERRIT_EVENT_TYPE == "patchset-created" || env.GERRIT_EVENT_TYPE == "comment-added")
    }
    return false
}

/************************************************************************************************************
 * Determine if the current build was triggered by a Merge event.
 * For Gerrit: Check if GERRIT_EVENT_TYPE is set to "ref-updated" or "change-merged"
 * For GitHub: Not implemented yet
**/
Boolean isMergeTriggered() {
    if (triggeredByGerrit()) {
        return (env.GERRIT_EVENT_TYPE == "ref-updated" || env.GERRIT_EVENT_TYPE == "change-merged")
    }
    return false
}

/************************************************************************************************************
 * Determine if Gerrit triggered the current build.
 *
 * @return bool saying if current build is triggered by Gerrit or not
**/
def triggeredByGerrit() {
    def isStartedByGerrit = currentBuild.rawBuild.getCause(com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritCause) != null
    return isStartedByGerrit
}

/************************************************************************************************************
 * Determine if GitHub branch triggered the current build.
 * Supported plugins: "Generic Webhook Trigger Plugin"
 *
 * @return bool saying if current build is triggered by GitHub branch or not
 */
def triggeredByGitHub() {
    if (currentBuild.rawBuild.getCause(org.jenkinsci.plugins.gwt.GenericCause) && env.x_github_event) {
        return true
    }
    return false
}

/************************************************************************************************************
 * set review score back to gerrit with message
*/
def gerritSetReview(changeNumber, String patchsetNumber, String message, Integer reviewScore) {
    if(changeNumber && patchsetNumber) {
        new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit review ${changeNumber},${patchsetNumber} -m '\"${message}\"' --verified ${reviewScore} ")
    }
}

/************************************************************************************************************
 * post comment to gerrit.
**/
def gerritPostComment(changeNumber, String patchsetNumber, String message) {
    if(changeNumber && patchsetNumber) {
        new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit review ${changeNumber},${patchsetNumber} -m '\"${message}\"' ")
    }
}

/************************************************************************************************************
 * Get reviewer's emails
 * @param changeNumber is number of change url
 * 
 * @return List of email from reviewers
**/
List getReviewersEmail(changeNumber) {
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --all-reviewers")
    def allReviewers = new jobUtils().parseJsonToMap(cmd)['allReviewers']
    return allReviewers.collect{it.email}.findAll{it}
}

/************************************************************************************************************
 * Get reviewer's emails
 * @param changeNumber is number of change url
 * 
 * @return List of email from reviewers
**/
List getChangedFiles(changeNumber) {
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set --files")
    def allFiles = new jobUtils().parseJsonToMap(cmd)['currentPatchSet']['files']     
    return allFiles.collect{it.file}.findAll{!it.contains('/COMMIT_MSG')}
}

/************************************************************************************************************
 * Get download type for an open change: cherry-pick or checkout
 * cherry-pick: when just a commit alone
 * checkout: when there are unmerged parents, or merging commit, or merged change.
 * @param changeNumber is number of change url
 * 
 * @return String download option: --cherry-pick or checkout
**/
def downloadOpenChanges(changeNumber, patchsetNumber, repoFile='', fetchThreads=4, dependencies=[]) {
    String gerritProject = getProject(changeNumber)
    String gerritCurrentRef = getCurrentRef(changeNumber)
    List gerritChangedFiles = getChangedFiles(changeNumber)

    if (repoFile) {
        List dpList = []
        String gerritBranch = getBranch(changeNumber)
        String downloadOption = getDownloadOption(changeNumber)
        String repoMainfestProject = new jobUtils().runShell("[ -d .repo/manifests/.git ] && echo \$(git --git-dir=.repo/manifests/.git --work-tree=.repo/manifests remote -v | grep fetch | rev | cut -d':' -f1 | rev | cut -d' ' -f1)").replaceAll(env.DEFAULT_GERRIT_PORT+'/','')
        dependencies.each{ dp ->
            String dpProject = getProject(dp)
            List dpChangedFiles = getChangedFiles(dp)
            if (dpProject.toLowerCase().contains('manifest') || 
                (repoMainfestProject.equalsIgnoreCase(dpProject) /* && dpChangedFiles.contains('.xml')*/)){
                String dpBranch = getBranch(dp)
                String dpCurrentRef = getCurrentRef(dp)
                new jobUtils().runShell("""
                                [ -d .repo/manifests/.git ] && rm -rf .repo/../* .repo/../.* || true
                                repo init --depth=1 -u ssh://${env.DEFAULT_GERRIT}:${env.DEFAULT_GERRIT_PORT}/${dpProject} -b ${dpBranch} -m ${repoFile}
                                repo forall -vc git reset --hard HEAD || true
                                pushd .repo/manifests &>/dev/null
                                git fetch ssh://${env.DEFAULT_GERRIT}:${env.DEFAULT_GERRIT_PORT}/${dpProject} ${dpCurrentRef} && git cherry-pick FETCH_HEAD
                                popd &>/dev/null
                                repo sync -d -q --force-sync -j${fetchThreads}
                                repo sync --force-sync -j${fetchThreads}
                """)
            } else {
                String dpPatchsetNumber = getCurrentPatchset(dp)
                String dpDownloadOption = getDownloadOption(dp)
                dpList.add(['project': dpProject, 'downloadOption': dpDownloadOption, 'downLoadRef': "${dp}/${dpPatchsetNumber}"])
            }
        }

        if (gerritProject.toLowerCase().contains('manifest') 
            || (repoMainfestProject.equalsIgnoreCase(gerritProject) /*&& gerritChangedFiles.contains('.xml')*/)){
            new jobUtils().runShell("""
                            #[ -d .repo/manifests/.git ] && rm -rf .repo/../* .repo/../.* || true
                            repo init --depth=1 -u ssh://${env.DEFAULT_GERRIT}:${env.DEFAULT_GERRIT_PORT}/${gerritProject} -b ${gerritBranch} -m ${repoFile}
                            repo forall -vc git reset --hard HEAD || true
                            pushd .repo/manifests &>/dev/null
                            git fetch ssh://${env.DEFAULT_GERRIT}:${env.DEFAULT_GERRIT_PORT}/${gerritProject} ${gerritCurrentRef} && git cherry-pick FETCH_HEAD
                            popd &>/dev/null
                            repo sync -d -q --force-sync -j${fetchThreads}
                            repo sync --force-sync -j${fetchThreads}
            """)
        }

        dpList.each{ dp ->
            new jobUtils().runShell("repo download ${dp.downloadOption} ${dp.project} ${dp.downLoadRef} || true", false)
        }

        if (!(gerritProject.toLowerCase().contains('manifest') 
            || repoMainfestProject.equalsIgnoreCase(gerritProject))){
            new jobUtils().runShell("repo download ${downloadOption} ${gerritProject} ${changeNumber}/${patchsetNumber}")
        }
    } else {
        dependencies.each{ dp ->
            String dpProject = getProject(dp)
            String dpCurrentRef = getCurrentRef(dp)
            new jobUtils().runShell("git fetch ssh://${env.DEFAULT_GERRIT}:${env.DEFAULT_GERRIT_PORT}/${dpProject} ${dpCurrentRef} && git checkout FETCH_HEAD || true", false)
        }
        new jobUtils().runShell("git fetch ssh://${env.DEFAULT_GERRIT}:${env.DEFAULT_GERRIT_PORT}/${gerritProject} ${gerritCurrentRef} && git checkout FETCH_HEAD")
    }

}

/************************************************************************************************************
 * Validate dependencies if they are valid
 * isRepoRequired=true: check with manifest
 * isRepoRequired=false: check with current change
 * @param Boolean isRepoRequired if the project is using repo tool
 * @param changeNumber is current commit's change number
 * @param String referencePoint is either repoFile of project or gerrit branch of current change.
 * @param List dependencies list of dependencies to validate 
 * 
 * @return String branch name
**/
List validateDependency(repoUrl, changeNumber, String referencePoint, List dependencies) {
    List invalidDependencies = []
    dependencies.each{ dp ->
        String dpProject = getProject(dp)
        String dpBranch = getBranch(dp)
        if (repoUrl != ''){
            String checkProjectBranch = new jobUtils().runShell("grep -rh ${dpProject} .repo/manifests/${referencePoint} | grep -Eo \"${dpBranch}\" | head -n1 || true").replaceAll('"','')
            if((!checkProjectBranch.trim().equals(dpBranch)) && (!repoUrl.contains(dpProject))) {
                invalidDependencies.add("${dp}--project:${dpProject}--branch:${dpBranch}")
            }
        }else {
            String gerritProject = getProject(changeNumber)
            if ((!dpProject.equals(gerritProject) || !dpBranch.equals(referencePoint))){
                invalidDependencies.add("${dp}--project:${dpProject}--branch:${dpBranch}")
            }
        }
    }
    return invalidDependencies
}

/************************************************************************************************************
 * Get commit's branch by change number
 * @param changeNumber is number of change url
 * 
 * @return String branch name
**/
String getBranch (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return new jobUtils().parseJsonToMap(cmd)['branch']
}

/************************************************************************************************************
 * Get commit's current patchset by change number
 * @param changeNumber is number of change url
 * 
 * @return String patchset number
**/
String getCurrentPatchset (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return new jobUtils().parseJsonToMap(cmd)['currentPatchSet']['number']
}

/************************************************************************************************************
 * Get commit's id by change number
 * @param changeNumber is number of change url
 * 
 * @return String commit revision
**/
String getRevision (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return new jobUtils().parseJsonToMap(cmd)['currentPatchSet']['revision']
}

/************************************************************************************************************
 * Get commit's current ref by change number
 * @param changeNumber is number of change url
 * 
 * @return String ref number
**/
String getCurrentRef (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return new jobUtils().parseJsonToMap(cmd)['currentPatchSet']['ref']
}

/************************************************************************************************************
 * Check if commit is still open
 * @param String env.DEFAULT_GERRIT is gerrit server name
 * @param changeNumber is number of change url
 * 
 * @return Boolean status
**/
Boolean isCommitOpen (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return (new jobUtils().parseJsonToMap(cmd)['open']!=null)?new jobUtils().parseJsonToMap(cmd)['open']:false
}

/************************************************************************************************************
 * Get all relation changes's information recursively (Those were submitted together)


 * @param changeNumber is number of change url
 *
 * @return List relationNumber,relationPatchsetNumber,relationPatchsetStatus (latest or rebase)
**/

List getRelationsInfos (changeNumber) {
    List rList = []
    String changePatchset
    String changeStatus
    while(true) {
        (changeNumber, changePatchset, changeStatus) = relationInfos(changeNumber)
        if ((changeNumber == null)||(changePatchset == null)||(changeStatus == null)) {
            break
        } else if (!isCommitOpen(changeNumber)){
            break
        } else {
            rList.add([changeNumber,changePatchset,changeStatus].join(','))
        }
    }
    
    return rList
}

/************************************************************************************************************
 * Get relation change number (the one was submitted together)
 * @param String gerritProject gerrit project/repo's name
 * 
 * @return List relationNumber,relationPatchsetNumber,relationPatchsetStatus (latest or rebase)
**/
def relationInfos (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=JSON --dependencies --current-patch-set")
    if (new jobUtils().parseJsonToMap(cmd)['dependsOn']){
        return [new jobUtils().parseJsonToMap(cmd)['dependsOn'][0]['number'], 
                new jobUtils().parseJsonToMap(cmd)['dependsOn'][0]['ref'].tokenize('/')[-1],
                (new jobUtils().parseJsonToMap(cmd)['dependsOn'][0]['isCurrentPatchSet'] == true)?'latest':'rebase']
    } else {
        return [null,null,null]
    }
}


/************************************************************************************************************
 * Check if commit needs to be rebased
 * @param changeNumber is number of change url
 * 
 * @return Boolean true: needs to rebase, false: is on latest
**/
Boolean isCommitRebasable (changeNumber){
    String latestRemoteRevision = getLatestRemoteRevision(getProject(changeNumber), getBranch(changeNumber))
    List parentRevisions = getParents(changeNumber)
    Boolean rValue = true
    parentRevisions.each { it ->
        if (it.trim().equals(latestRemoteRevision.trim())){
            rValue = false
        }
    }
    return rValue
}

/************************************************************************************************************
 * Check if commit can be submitted
 * @param changeNumber is number of change url
 * 
 * @return Boolean true: commit can be submitted
**/
Boolean isCommitSubmittable (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --submit-records")
    return (new jobUtils().parseJsonToMap(cmd)['submitRecords'][0]['status']?.trim()=='OK')?true:false
}

/************************************************************************************************************
 * Submit a change if it is submittable
 * @param changeNumber is number of change url
 * @param String patchsetNumber
 * 
 * @return none
**/
def submitChange (changeNumber, patchsetNumber) {
    if (isCommitSubmittable(changeNumber)){
        new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit review --submit ${changeNumber},${patchsetNumber}")
    }
}

/************************************************************************************************************
 * Check if this is merging commit from one branch to the other, it will have 2 or more parents
 * @param changeNumber is number of change url
 * 
 * @return none
**/
def isMergingCommit (changeNumber) {
    return (getParents(changeNumber).size()>1)?true:false
}

/************************************************************************************************************
 * Get download type for an open change: cherry-pick or checkout
 * cherry-pick: when just a commit alone
 * checkout: when there are unmerged parents, or merging commit, or merged change.
 * @param changeNumber is number of change url
 * 
 * @return String download option: --cherry-pick or checkout
**/
String getDownloadOption(changeNumber, Boolean isRepoRequired=true) {
    if (!isCommitOpen(changeNumber) || isMergingCommit(changeNumber)){
            return 'checkout'
    } else {
        List parentRevisions = getParents(changeNumber)
        parentRevisions.each { it->
            if (isCommitOpen(it)){
                return 'checkout'
            }
        }
    }
    return isRepoRequired?'--cherry-pick':'cherry-pick'
}

/************************************************************************************************************
 * Get commit message from change number
 * @param changeNumber is number of change url
 * 
 * @return String commit's message
**/
String getCommitMsg(changeNumber) {
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --commit-message --current-patch-set")
    def commitMessage = new jobUtils().parseJsonToMap(cmd)['commitMessage']
    return (commitMessage != null)?commitMessage:''
}

/************************************************************************************************************
 * Get gerrit project (repo name) from change number
 * @param String env.DEFAULT_GERRIT is gerrit server name
 * @param changeNumber is number of change url
 * 
 * @return String of gerrit project
**/
String getProject (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return new jobUtils().parseJsonToMap(cmd)['project']
}

/************************************************************************************************************
 * Get commit's status by change number: MERGED or NEW
 * @param String env.DEFAULT_GERRIT is gerrit server name
 * @param changeNumber is number of change url
 * 
 * @return String status
**/
String getCommitStatus (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return new jobUtils().parseJsonToMap(cmd)['status']
}

/************************************************************************************************************
 * Get commit's url by change number
 * @param String env.DEFAULT_GERRIT is gerrit server name
 * @param changeNumber is number of change url
 * 
 * @return String commit ID
**/
String getUrl (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return new jobUtils().parseJsonToMap(cmd)['url'].trim()
}

/************************************************************************************************************
 * Get current parents from change number
 * @param String env.DEFAULT_GERRIT is gerrit server name
 * @param changeNumber is number of change url
 * 
 * @return List of parent's revisions
**/
List getParents (changeNumber){
    String cmd = new jobUtils().runShell("ssh -p ${env.DEFAULT_GERRIT_PORT} ${env.DEFAULT_GERRIT} gerrit query ${changeNumber} --format=json --current-patch-set")
    return new jobUtils().parseJsonToMap(cmd)['currentPatchSet']['parents'].findAll{it}
}


/************************************************************************************************************
 * Compose final url for gerrit REST request
 * @param String input String
 * 
 * @return output String
**/
String getGerritRestUrl(strInput) {
    return "https://${env.DEFAULT_GERRIT}/a/${strInput}"
}

/************************************************************************************************************
 * Get latest commit on remote branch
 * @param String gerritProject gerrit project/repo's name
 * @param String gerritBranch branch to check
 * 
 * @return String revision
**/
String getLatestRemoteRevision (String gerritProject, String gerritBranch){
    queueUrl = "projects/${gerritProject.replaceAll('/','%2F')}/branches/${gerritBranch.replaceAll('/','%2F')}"
    Map restResponse = new jobUtils().parseJsonToMap(new jobUtils().httpRequest(getGerritRestUrl(queueUrl)))
    return restResponse['revision']
}

/************************************************************************************************************
 * Get recursive dependency list from "Depends-On: xxxx,yyyy" inside commit message. Each commit can be separate by space, comma or semicolon
 * If the commits in depends-on list, also have Depends-On, will consider them as well.
 * 
 * @return List of dependency by change numbers 
**/
@groovy.transform.Field
dependencyList = []
@groovy.transform.Field
dependencyCheckList = []

List getDependencyList(script, changeNumber, List relationInfos=[]){
    def returnList = []
    def changeNumberList = [changeNumber]
    relationInfos.each{ ri ->
        changeNumberList.add(ri.tokenize(',')[0])
    }
    changeNumberList.each{ cn ->
        getRecursiveDependencies(cn)
    }
    returnList = dependencyList.unique()
    returnList.remove(changeNumber)
    return returnList
}


/************************************************************************************************************
 * Get recursive dependency list from "Depends-On: xxxx,yyyy" inside commit message. Each commit can be separate by space, comma or semicolon
 * If the commits in depends-on list, also have Depends-On, will consider them as well.
 * @param changeNumber
 * 
 * @return List of dependency by change numbers 
**/
def getRecursiveDependencies(changeNumber) {
    List returnDepends = []
    List dependencies = getDependencies(changeNumber)
    dependencyList += dependencies
    if (dependencies.size() > 0) {
        dependencies.each {
            if (!dependencyCheckList.contains(it)) {
                dependencyCheckList.add(it)
                returnDepends += getRecursiveDependencies(it)
            }
            
        }
    }
    return dependencyList.unique()+returnDepends.unique()
}

/************************************************************************************************************
 * Get dependency list from "Depends-On: xxxx,yyyy" inside commit message. Each commit can be separate by space, comma or semicolon
 * @param changeNumber
 * 
 * @return List of dependency by change numbers 
**/

List getDependencies(changeNumber) {
    String commitMsg = (getCommitMsg(changeNumber).replaceAll('[;,]',' ') =~ /(?i)depends-on:.*/).findAll()[0]
    return (commitMsg == null)?[]:commitMsg.replaceAll('[;,]',' ').split(':')[-1].tokenize(' ')
                                    .unique().collect{ (isCommitOpen(it)==true)?it:null}.findAll{it}
}

