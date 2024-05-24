import com.eb.lib.btrfsUtils
import com.eb.lib.jobUtils
import com.eb.lib.gerritUtils
import com.eb.lib.jiraUtils

def call(Map parameters) {
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    new btrfsUtils().cloneSubvolumeWS(variantConfig.subvolumeBaselineDir, variantConfig.workingDir)

    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            if(variantConfig.runScript){
                new jobUtils().scriptRun(variantConfig.runScript, variantConfig.scriptNamePrefix)
            }

            if (variantConfig.isRepoRequired) {
                new jobUtils().runShell("rm -rf .repo/manifests* *.log *.xml Script*.sh configMap.yaml")
                checkout(changelog: false, 
                    poll: false,
                    scm: [$class: 'RepoScm',
                        manifestRepositoryUrl: variantConfig.repoUrl,
                        manifestFile: variantConfig.repoFile,
                        manifestBranch: variantConfig.repoBranch,
                        jobs: variantConfig.fetchThreads,
                        currentBranch: true,
                        quiet: true,
                        forceSync: true,
                        noTags: true,
                        cleanFirst: true])
            } else {
                new jobUtils().runShell("rm -rf *.log Script*.sh configMap.yaml")
                checkout(changelog: false,
                    poll: false,
                    scm: [$class: 'GitSCM',
                        branches: [[name: variantConfig.gitBranch]],
                        userRemoteConfigs: [[url: variantConfig.gitUrl, 
                                            credentialsId: variantConfig.gitUrl]],
                        extensions: [
                            [$class: 'CloneOption', noTags: true, shallow: false],
                        ]
                    ]
                )
            }

            if(configMap.isMergeTriggered) {
                if ((stageConfig.checkList?.isBuilt?:
                        (configMap[variant]?.checkList?.isBuilt?:
                            (configMap.checkList?.isBuilt?:
                                configMap.general.checkList?.isBuilt)))?.equalsIgnoreCase('enabled')) {
                    if(variantConfig.isRepoRequired){
                        if (!new jobUtils().isBuildRequired(variantConfig.repoReleaseFile)){
                            buildState = "Commit Had Been Built Earlier"
                            manager.createSummary("aborted.png").appendText(buildState, false, false, false, "black")
                            script.currentBuild.description = buildState
                            new jobUtils().throwPipelineException(buildState, 'ABORTED')
                        }
                    }
                }
            }

            if (configMap.isChangeTriggered) {
                /* If job is triggered by a new commit event or a comment event, that is for verify job or get_flash*/
                List relationsInfos = new gerritUtils().getRelationsInfos(configMap.env.GERRIT_CHANGE_NUMBER)
                List dependencies = new gerritUtils().getDependencyList(this, configMap.env.GERRIT_CHANGE_NUMBER)

                if(relationsInfos){
                    script.configMap.feedbackComposer['INFO'].add("\n\t\t- Relations: ${relationsInfos.join(' -- ')}".toString())
                }
                if(dependencies){
                    script.configMap.feedbackComposer['INFO'].add("\n\t\t- Depends-On: ${dependencies.join(' -- ')}".toString())
                }

                if (variantConfig.isRepoRequired) {
                    new gerritUtils().downloadOpenChanges(configMap.env.GERRIT_CHANGE_NUMBER, configMap.env.GERRIT_PATCHSET_NUMBER,
                                                    variantConfig.repoFile, variantConfig.fetchThreads, dependencies)
                    new jobUtils().runShell("repo manifest -o ${variantConfig.repoReleaseFile} -r")

                } else {
                    new gerritUtils().downloadOpenChanges(configMap.env.GERRIT_CHANGE_NUMBER, configMap.env.GERRIT_PATCHSET_NUMBER, dependencies)
                }

                String buildState = ""
                def latestPatchsetNumber = new gerritUtils().getCurrentPatchset(configMap.env.GERRIT_CHANGE_NUMBER)

                List jiraIds = new jiraUtils().getJiraIDs(configMap.env.GERRIT_CHANGE_COMMIT_MESSAGE.toString())
                /* To Disable Any Check, change true to false*/
                if ((stageConfig.checkList?.latestPatchset?:
                        (configMap[variant]?.checkList?.latestPatchset?:
                            (configMap.checkList?.latestPatchset?:
                                configMap.general?.checkList?.latestPatchset)))?.equalsIgnoreCase('enabled')
                    && (latestPatchsetNumber.toInteger() > configMap.env.GERRIT_PATCHSET_NUMBER.toInteger())) {
                    /** Check if current patchset is the latest. If not, abort the build. remove verified score**/
                    buildState = "Current Patchset ${configMap.env.GERRIT_PATCHSET_NUMBER} Is Not The Latest (${latestPatchsetNumber})"
                    script.currentBuild.description = buildState
                    new jobUtils().throwRuntimeException(buildState)
                } else if((stageConfig.checkList?.rebase?:(configMap[variant]?.checkList?.rebase?:
                                (configMap.checkList?.rebase?:
                                    configMap.general.checkList?.rebase)))?.equalsIgnoreCase('enabled')
                        && (relationsInfos.join(' ').toLowerCase().contains('rebase')
                        || new gerritUtils().isCommitRebasable(configMap.env.GERRIT_CHANGE_NUMBER))) {
                    /** Check if commit is up-to-date or need to rebase. If not, give verify -1 and abort the build**/
                    buildState = "Current Commit Needs To Rebase To The Tip Of ${configMap.env.GERRIT_BRANCH}"
                    script.configMap.verifyScore = -1
                    script.currentBuild.description = buildState
                    new jobUtils().throwRuntimeException(buildState)
                } else if ((stageConfig.checkList?.wipState?:
                                (configMap[variant]?.checkList?.wipState?:
                                    (configMap.checkList?.wipState?:
                                        configMap.general.checkList?.wipState)))?.equalsIgnoreCase('enabled')
                        && (configMap.env.GERRIT_EVENT_TYPE == "wip-state-changed" && configMap.env.GERRIT_CHANGE_WIP_STATE == "true")) {
                    /** Check if 'work-in-progress' state changes, then no build is required**/
                    buildState = "Work-In-Progress State Change"
                    script.currentBuild.description = buildState
                    new jobUtils().throwRuntimeException(buildState)
                } else if ((stageConfig.checkList?.jira?:
                                (configMap[variant]?.checkList?.jira?:
                                    (configMap.checkList?.jira?:
                                        configMap.general.checkList?.jira)))?.equalsIgnoreCase('enabled')
                        && jiraIds.size() < 1){
                    buildState = "No JIRA Ticket Found!!!"
                    script.configMap.verifyScore = -1
                    script.currentBuild.description = buildState
                    new jobUtils().throwRuntimeException(buildState)
                } else if ((stageConfig.checkList?.dependsOn?:
                                (configMap[variant]?.checkList?.dependsOn?:
                                    (configMap.checkList?.dependsOn?:
                                        configMap.general.checkList?.dependsOn)))?.equalsIgnoreCase('enabled')
                        && dependencies.size() > 0){
                    List invalidDependencies
                    if (variantConfig.isRepoRequired) {
                        invalidDependencies = new gerritUtils().validateDependency(variantConfig.repoUrl, configMap.env.GERRIT_CHANGE_NUMBER, variantConfig.repoFile, dependencies)
                    }else {
                        invalidDependencies = new gerritUtils().validateDependency('', configMap.env.GERRIT_CHANGE_NUMBER, configMap.env.GERRIT_BRANCH, dependencies)
                    }
                    if (invalidDependencies.size() > 0) {
                        buildState = "Invalid Dependencies Found (Wrong Branch Or Project): " + invalidDependencies.join(' && ')
                        script.configMap.verifyScore = -1
                        script.currentBuild.description = buildState
                        new jobUtils().throwRuntimeException(buildState)
                    }
                } else {
                    script.configMap.feedbackComposer['CHECK-LIST'].add("\t\t[v] Latest Patchset ${configMap.env.GERRIT_PATCHSET_NUMBER}/${latestPatchsetNumber}".toString())
                    script.configMap.feedbackComposer['CHECK-LIST'].add("\t\t[v] No Rebase Required".toString())
                    script.configMap.feedbackComposer['CHECK-LIST'].add("\t\t[v] Jira Ticket: ${jiraIds.join(' ')}".toString())
                }
            }

            currentBuild.description = "${currentBuild.description}\n${variant}:${variantConfig.buildVersion}"
        }
    }
}