import com.eb.lib.jobUtils

def call(Map parameters) {
    def jobUtils = new jobUtils()
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    String changeLog
    def changeLogTypes = (variantConfig.changeLogTypes?:
                            (stageConfig.changeLogTypes?:
                                (configMap[variant]?.changeLogTypes?:
                                    (configMap.changeLogTypes?:
                                        (configMap.general.changeLogTypes?:configMap.pipelineType))))).trim().replaceAll("[,;]",' ').tokenize(' ')

    String versionTemplateBase = variantConfig.versionTemplate.replaceAll('%s','')
    Date date = new Date()
    def repoCheckTmpDir = "repoCheckTmpDir_${variant}_" + date.format("yyyy-MM-dd_HH-mm")

    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            if(variantConfig.runScript){
                jobUtils.scriptRun(variantConfig.runScript, variantConfig.scriptNamePrefix)
            }
            if(variantConfig.isRepoRequired){
                new jobUtils().runShell("repo manifest -o ${variantConfig.repoReleaseFile} -r")

                checkout([$class: 'GitSCM',
                        branches: [[name: variantConfig.repoBranch]],
                        userRemoteConfigs: [[url: variantConfig.repoUrl,
                                            credentialsId: variantConfig.repoCreds]],
                        extensions: [
                            [$class: 'CloneOption', noTags: false],
                            [$class: 'RelativeTargetDirectory', relativeTargetDir: repoCheckTmpDir],
                        ]
                    ]
                )

                //jobUtils.runShell("git --git-dir=${repoCheckTmpDir}/.git --work-tree=${repoCheckTmpDir} fetch --tags --quiet &>/dev/null", false)

                changeLogTypes.each{ clt ->
                    String prevBaseline = ''
                    String allTags=jobUtils.runShell("git --git-dir=${repoCheckTmpDir}/.git --work-tree=${repoCheckTmpDir} log --oneline --decorate=short --pretty=%d | \
                                    grep \"tag:\\s${clt}\" | grep \"tag:\\s${versionTemplateBase}\" | head -n1 2>/dev/null", false)
                    allTags?.replaceAll("[()]","").tokenize(',').each{ tg ->
                        if (tg.contains('tag: ' + versionTemplateBase) && (prevBaseline == '')){
                            prevBaseline = tg.tokenize(':')[-1].trim()
                        }
                    }

                    changeLog = """(*** ${variantConfig.buildVersion} Is The First ${clt.toUpperCase()} Baseline \n(Reason: Cannot Find \"tag: ${clt}\" && \"tag: ${versionTemplateBase}\" In Tag List)***)"""
                    if (prevBaseline != "") {
                        changeLog = "(*** ${variant.toUpperCase()}: CHANGES FROM LAST ${clt.toUpperCase()} ${prevBaseline} TO ${variantConfig.buildVersion} ***)\n"
                        checkoutLog = jobUtils.runShell("git --git-dir=${repoCheckTmpDir}/.git --work-tree=${repoCheckTmpDir} checkout ${prevBaseline}",false)
                        if (checkoutLog.contains("error: pathspec")){
                            changeLog = changeLog + "Can't find diff because git-tag ${prevBaseline} can't be checked out!"
                        }
                        def diffManifests = jobUtils.runShell("repo diffmanifests ${repoCheckTmpDir}/${variantConfig.repoReleaseFile} ${variantConfig.repoReleaseFile}", false)
                        if (diffManifests != ""){
                            changeLog = changeLog + diffManifests
                        } else {
                            changeLog = changeLog + "No diff found."
                        }
                    }
                    script.writeFile file: "release_note_${clt}_${variantConfig.buildVersion}_${variant}.log", text: changeLog
                }
                jobUtils.runShell("rm -rf ${repoCheckTmpDir}*")
            } else {
                def diffGitLogs = jobUtils.runShell("git log --oneline --graph --all --decorate --abbrev-commit HEAD..origin/${variantConfig.remoteBranch}", false)
                changeLog = "(*** Differences Between This Build And Remote origin/${variantConfig.remoteBranch} ***)"
                if (diffGitLogs != ""){
                    changeLog = changeLog + diffGitLogs
                } else {
                    changeLog = changeLog + "No diff found."
                }
                script.writeFile file: "release_note_${variantConfig.buildVersion}_${variant}.log", text: changeLog
            }
        }
    }
}