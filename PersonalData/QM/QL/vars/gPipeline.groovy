import com.eb.lib.jobUtils
import com.eb.lib.gerritUtils
import com.eb.lib.commonEnvironment
import com.eb.lib.PipelineEnvironmentException

def configMap
def call(Map parameters) {
    try {
        def jobUtils = new jobUtils()
        def commonEnvironment = new commonEnvironment()
        configMap = commonEnvironment.setupConfiguration(parameters.projectConfig)
        pipeline {
            this.agent { label configMap.general.defaultNode }
            this.environment { 
                DEFAULT_JIRA = "${configMap.jiraServer?:(configMap.general.jiraServer?:'https://jira.elektrobit.com')}/browse/"
                DEFAULT_GERRIT = "${env.GERRIT_NAME?:(configMap.gerritHost?:(configMap.general.gerritHost?:configMap.scm.host.toString()))}"
                DEFAULT_GERRIT_PORT = "${env.GERRIT_PORT?:(configMap.gerritPort?:(configMap.general.gerritPort?:configMap.scm.port.toString()))}"
                DEFAULT_DOCKER = """${configMap.docker?(configMap.docker.registry?:configMap.general.docker.registry)+'/'+configMap.docker.image+':'+configMap.docker.tag:
                                    configMap.general.docker.registry+'/'+configMap.general.docker.image+':'+configMap.general.docker.tag}"""
                DEFAULT_DOCKER_ARGS = """${configMap.docker?jobUtils.runShell("echo ${configMap.docker.dargs}"):jobUtils.runShell("echo ${configMap.general.docker.dargs}")}"""
                DEFAULT_CREDENTIAL = this.credentials("${configMap.scm.creds.toString()?:(configMap.creds.toString()?:configMap.general.creds.toString())}")
            }

            this.options {
                skipDefaultCheckout()
                timestamps()
                timeout(time: configMap.jobConfig?.timeOut?:configMap.general.timeOut, unit: 'SECONDS')
                buildDiscarder logRotator(
                                    artifactDaysToKeepStr: configMap.jobConfig?.buildDiscarder?.artifactDaysToKeepStr?.toString()?:'',
                                    artifactNumToKeepStr: configMap.jobConfig?.buildDiscarder?.artifactNumToKeepStr?.toString()?:'',
                                    daysToKeepStr: configMap.jobConfig?.buildDiscarder?.daysToKeepStr?.toString()?:'',
                                    numToKeepStr: configMap.jobConfig?.buildDiscarder?.numToKeepStr?.toString()?:'')
                throttleJobProperty categories: [configMap.jobConfig?.throttleJobProperty?.category?:''],
                                    limitOneJobWithMatchingParams: false,
                                    maxConcurrentPerNode: configMap.jobConfig?.throttleJobProperty?.maxConcurrentPerNode?:0,
                                    maxConcurrentTotal: configMap.jobConfig?.throttleJobProperty?.maxConcurrentTotal?:0,
                                    paramsToUseForLimit: '',
                                    throttleEnabled: configMap.jobConfig?.throttleJobProperty?.throttleEnabled?:false,
                                    throttleOption: configMap.jobConfig?.throttleJobProperty?.throttleOption?:'project'
            }

            this.triggers {
                cron configMap.jobConfig?.trigger?.cron?:''
                upstream configMap.jobConfig?.upstream?:''
            }

            this.parameters {
                string(name: 'CAUSED_BY', defaultValue: '', description: 'Information About Triggered Cause')
                string(name: 'RUN_ON_VERSION', defaultValue: '', description: 'Choose Particular Version To Run')
                string(name: 'RUN_FOR_VARIANTS', defaultValue: '', description: 'Choose Particular Variants To Run, Separated By Space or Comma')
                string(name: 'RERUN_FROM_STAGE', defaultValue: '', description: 'Choose Particular Stage To ReRun The Build (Case Sensitive)')
                string(name: 'STAGES_TO_SKIP', defaultValue: '', description: 'Stages Will Be Skipped, Separated By Space or Comma (Case Sensitive)')
                text(name: 'DYNAMIC_CONFIG', defaultValue: '', description: 'Dynamic Config By User')
                booleanParam(name: 'FORCE_RUN', defaultValue: false, description: 'Force Run, Disregard All The Check')
                booleanParam(name: 'CLEAN_BUILD', defaultValue: false, description: 'Force Clean Build')
            }

            stages {
                stage("Initialization") {
                    // Prepares the initial workspace like checking out sources, configuring system, ...
                    // To be implemented in project/variant specific library.
                    steps {
                        deleteDir()     
                        script {
                            commonEnvironment.setJenkinsJobConfig(this, configMap)
                            
                            if(params.CLEAN_BUILD){
                                configMap.isCleanBuild = true
                            }

                            currentBuild.result = currentBuild.result?:'SUCCESS'
                            currentBuild.description = ''
                            configMap.buildTimeStamp = new Date().format("yyyyMMdd_HHmm").toString()
                            configMap.releaseRootDir = configMap.releaseRootDir?:(configMap.general.releaseRootDir?:'')
                            configMap.emailRecipient = configMap.general.email.defaultEmailReceiver
                            configMap.buildVersionMap = configMap.buildVersionMap?:[:]
                            configMap.feedbackComposer = [:]
                            configMap.feedbackComposer['INFO'] = []
                            configMap.feedbackComposer['CHECK-LIST'] = []
                            /*Getting the best node here for each variant*/
                            if(params.RUN_FOR_VARIANTS){
                                configMap.variants = params.RUN_FOR_VARIANTS
                            }
                            configMap.variants = (configMap.variants?
                                                    ((configMap.variants instanceof String)?configMap.variants?.replaceAll("[,;]",' ').tokenize(' '):configMap.variants):
                                                        configMap.general.projectName?.replaceAll("[,;]",' ').tokenize(' '))
                            configMap.nodeMap = configMap.nodeMap?:commonEnvironment.getBestNode(configMap.runNode?:
                                                                                                    configMap.general.defaultNode, 
                                                                                                        configMap.variants)
                            /*Getting workspace for each variant*/
                            configMap.wsMap = configMap.wsMap?:
                                                commonEnvironment.getWorkingDir(configMap)

                            /*Getting baseline Subvolume for each variant*/
                            configMap.subvolumeBaselineMap = configMap.subvolumeBaselineMap?:
                                                                commonEnvironment.getSubvolumeBaselineDir(configMap)

                            commonEnvironment.setGlobalConfigMap(this, configMap)
                            /*END OF *Serve The Back Up Strategy For Rerunning The Build **/

                            /*Print configs*/
                            commonEnvironment.printConfiguration(configMap)
                            Boolean isRerunStage = false
                            Map isMachineSet = [:]
                            if(params.STAGES_TO_SKIP){
                                manager.createSummary("clipboard.png").appendText("""<b>Skipped Stages:</b> ${params.STAGES_TO_SKIP}""", 
                                                                                        false, false, false, "black")
                            }
                            configMap.stages.each { kMainStage, vMainStage ->
                                stage(kMainStage) {
                                    Map buildMap = [:]
                                    if(configMap.stages[kMainStage].status){
                                        if((!configMap.stages[kMainStage].status.equals('SUCCESS')
                                            &&configMap.stages[kMainStage].state.equalsIgnoreCase('enabled'))
                                            ||(params.RERUN_FROM_STAGE?.trim().equalsIgnoreCase(kMainStage.toString()))){
                                            if(!isRerunStage){
                                                if(configMap.rerunFromBuild?.buildUrl){
                                                    manager.createSummary("clipboard.png").appendText("""<b>Rerun From Build:</b>
                                                        <a href="${configMap.rerunFromBuild?.buildUrl}">${configMap.rerunFromBuild?.buildNumber}</a>
                                                        <br><b>Rerun From Stage:</b> ${kMainStage}""", false, false, false, "black")
                                                    configMap.feedbackComposer['INFO'].add("\n\t\t- ReRun From (Stage:${kMainStage}): ${configMap.rerunFromBuild?.buildUrl}".toString())
                                                    
                                                    currentBuild.description = "Rerun From Build: ${configMap.rerunFromBuild?.buildNumber}"
                                                }
                                            }
                                            isRerunStage = true
                                        }
                                        if (isRerunStage){
                                            configMap.stages[kMainStage].status = 'UNKNOWN'
                                        }
                                    } else {
                                        configMap.stages[kMainStage].status = 'UNKNOWN'
                                    }
                                    /* Check and run only stage is enabled, stage is successful, or always stage */
                                    if ((vMainStage.state.equalsIgnoreCase('enabled') 
                                            && currentBuild.result.equalsIgnoreCase('SUCCESS')
                                                && configMap.stages[kMainStage].status.equals('UNKNOWN')
                                                    && !params.STAGES_TO_SKIP?.replaceAll('[;,]',' ').tokenize(' ').contains(kMainStage)) 
                                        || vMainStage.state.equalsIgnoreCase('always')
                                        || params.FORCE_RUN) {
                                        configMap.stageConfig = vMainStage
                                        commonEnvironment.printConfiguration(vMainStage, "(STAGE:${kMainStage})")
                                        configMap.variantConfig = [:] // Access to each variant's config inside stage throughout pipeline
                                        (vMainStage.parallel?:(vMainStage.sequential?:["${(configMap.variants.size()==1)?configMap.variants[0]:kMainStage}":vMainStage])).each { kSubStage, vSubStage ->
                                            String subStageName = configMap.variants.contains(kSubStage)?[kMainStage,kSubStage].join('/'):kSubStage

                                            /*Getting build Version for each variant*/
                                            vSubStage.versionTemplate = (vSubStage.versionTemplate?:
                                                                            (vMainStage.versionTemplate?:
                                                                                (configMap[kSubStage]?.versionTemplate?:
                                                                                    (configMap.versionTemplate?:
                                                                                        (configMap.general.versionTemplate?:
                                                                                            (configMap.projectName?:
                                                                                                configMap.general.projectName).toUpperCase().trim().replace(' ','_')+"_%s"
                                                                                                    ))))).toString()
                                            /*buildVersion: Access to buildVersion for each variant in each stage*/
                                            vSubStage.buildVersion = configMap.buildVersionMap[kSubStage]?:String.format("${vSubStage.versionTemplate}", "${configMap.buildTimeStamp}_${env.BUILD_NUMBER}")
                                            if (configMap.isChangeTriggered) {
                                                vSubStage.buildVersion = configMap.buildVersionMap[kSubStage]?:"${vSubStage.buildVersion}-${configMap.env.GERRIT_CHANGE_NUMBER}-${configMap.env.GERRIT_PATCHSET_NUMBER}".toString()
                                            }
                                            configMap.buildVersionMap[kSubStage] = configMap.buildVersionMap[kSubStage]?:vSubStage.buildVersion

                                            /*Run node for each variant in each stage*/
                                            vSubStage.runNode = (configMap.variants.contains(kSubStage)?configMap.nodeMap[kSubStage]:configMap.nodeMap.values()[0]).toString()
                                            /*workingDir: working directory for each variant in each stage*/
                                            vSubStage.workingDir = (configMap.variants.contains(kSubStage)?configMap.wsMap[kSubStage]:configMap.wsMap.values()[0]).toString()
                                            /*subvolumeBaselineDir: btrfs Subvolume Baseline  directory for each variant in each stage*/
                                            vSubStage.subvolumeBaselineDir = (configMap.variants.contains(kSubStage)?configMap.subvolumeBaselineMap[kSubStage]:configMap.subvolumeBaselineMap.values()[0]).toString()
                                            /*dockerImage: Access to docker image for each variant in each stage*/
                                            vSubStage.dockerImage = (vSubStage.docker?.image?
                                                                    ((vSubStage.docker.registry?:(vMainStage.docker?.registry?:
                                                                    (configMap[kSubStage]?.docker?.registry?:(configMap.general.docker?.registry))))
                                                                    +'/'+vSubStage.docker.image+':'
                                                                    +vSubStage.docker.tag):
                                                                    (vMainStage.docker?.image?
                                                                    ((vMainStage.docker?.registry?:(configMap[kSubStage]?.docker?.registry?:
                                                                    (configMap.docker?.registry?:configMap.general.docker?.registry)))
                                                                    +'/'+vMainStage?.docker.image+':'
                                                                    +vMainStage.docker.tag):env.DEFAULT_DOCKER)).toString()
                                            /*dockerArgs: Access to docker arguments for each variant in each stage*/
                                            vSubStage.dockerArgs = (vSubStage.docker?.dargs?jobUtils.runShell("echo ${vSubStage.docker?.dargs}"):
                                                                        (vMainStage.docker?.dargs?jobUtils.runShell("echo ${vMainStage.docker?.dargs}"):
                                                                            (configMap[kSubStage]?.docker?.dargs?jobUtils.runShell("echo ${configMap[kSubStage]?.docker?.dargs}"):
                                                                                env.DEFAULT_DOCKER_ARGS))).toString()
                                                                                    .replaceAll("!buildVersion!", vSubStage.buildVersion)
                                                                                    .replaceAll("!workingDir!", vSubStage.workingDir)
                                                                                    .replaceAll("!runNode!", vSubStage.runNode)
                                                                                    .replaceAll("!variant!", kSubStage)
                                                                                    .replaceAll("!pipelineType!", configMap.pipelineType)
                                                                                    .replaceAll("!isCleanBuild!", configMap.isCleanBuild.toString())
                                                                                    .replaceAll("!releaseRootDir!", configMap.releaseRootDir)
                                                                                    .replaceAll("!WORKSPACE!", env.WORKSPACE)
                                            /*runScript: Access to addtional shell scripts or commands for each variant in each stage*/
                                            vSubStage.runScript = (vSubStage.runScript?:
                                                                    (vMainStage.runScript?:
                                                                        (configMap[kSubStage]?.runScript?:
                                                                            (configMap.runScript?:
                                                                                (configMap.general.runScript?:''))))).toString()
                                                                                    .replaceAll("!buildVersion!", vSubStage.buildVersion)
                                                                                    .replaceAll("!workingDir!", vSubStage.workingDir)
                                                                                    .replaceAll("!runNode!", vSubStage.runNode)
                                                                                    .replaceAll("!variant!", kSubStage)
                                                                                    .replaceAll("!pipelineType!", configMap.pipelineType)
                                                                                    .replaceAll("!isCleanBuild!", configMap.isCleanBuild.toString())
                                                                                    .replaceAll("!releaseRootDir!", configMap.releaseRootDir)
                                                                                    .replaceAll("!WORKSPACE!", env.WORKSPACE)

                                            /*scriptNamePrefix: runScript content will be saved into file with name scriptNamePrefix_randomstring_timestamp.sh*/
                                            vSubStage.scriptNamePrefix = "Script_${kMainStage}_${kSubStage}".toString()

                                            /*isRepoRequired: Check if repo is the tool to use to fetch workspace*/
                                            vSubStage.isRepoRequired = vSubStage.repo?true:
                                                                            (vMainStage.repo?true:
                                                                                (configMap[kSubStage]?.repo?true:
                                                                                    (configMap.repo?true:
                                                                                        (configMap.general?.repo?true:false))))
                                            if(vSubStage.git?:(vMainStage.git?:(configMap[kSubStage]?.git?:(configMap.git?:'')))){
                                                vSubStage.isRepoRequired = false
                                            }

                                            if (vSubStage.isRepoRequired) {
                                                /*fetchThreads: how many threads to use during repo sync*/
                                                vSubStage.fetchThreads = vSubStage.repo?.fetchThreads?:
                                                                            (vMainStage.repo?.fetchThreads?:
                                                                                (configMap[kSubStage]?.repo?.fetchThreads?:
                                                                                    (configMap.repo?.fetchThreads?:
                                                                                        (configMap.general.fetchThreads?:4))))
                                                /*repoFile: Main repo file use to repo init*/
                                                vSubStage.repoFile = vSubStage.repo?.repoFile?:
                                                                        (vMainStage.repo?.repoFile?:
                                                                            (configMap[kSubStage]?.repo?.repoFile?:
                                                                                (configMap.repo?.repoFile?:
                                                                                    configMap.general?.repo?.repoFile)))
                                                /*repoReleaseFile: Repo file name to release the manifest*/
                                                vSubStage.repoReleaseFile = vSubStage.repo?.repoReleaseFile?:
                                                                                (vMainStage.repo?.repoReleaseFile?:
                                                                                    (configMap[kSubStage]?.repo?.repoReleaseFile?:
                                                                                        (configMap.repo?.repoReleaseFile?:
                                                                                            configMap.general?.repo?.repoReleaseFile)))
                                                /*repoUrl: Repo url to do repo init*/                           
                                                vSubStage.repoUrl = vSubStage.repo?.repoUrl?:
                                                                        (vMainStage.repo?.repoUrl?:
                                                                            (configMap[kSubStage]?.repo?.repoUrl?:
                                                                                (configMap.repo?.repoUrl?:
                                                                                    (configMap.general?.repo?.repoUrl?:
                                                                                        configMap.scm.url))))
                                                /*repoBranch: Repo branch to do repo init*/                           
                                                vSubStage.repoBranch = params.RUN_ON_VERSION?:
                                                                        (vSubStage.repo?.repoBranch?:
                                                                            (vMainStage.repo?.repoBranch?:
                                                                                (configMap[kSubStage]?.repo?.repoBranch?:
                                                                                    (configMap.repo?.repoBranch?:
                                                                                        (configMap.general.repo?.repoBranch?:
                                                                                            configMap.scm.commit)))))
                                                /*repoCreds: Credential used for repo tool*/
                                                vSubStage.repoCreds = vSubStage.repo?.repoCreds?:
                                                                        (vMainStage.repo?.repoCreds?:
                                                                            (configMap[kSubStage]?.repo?.repoCreds?:
                                                                                (configMap.repo?.repoCreds?:
                                                                                    (configMap.general?.repo?.repoCreds?:
                                                                                        configMap.scm.creds))))
                                            } else {
                                                /*gitBranch: branch to do git clone or checkout*/
                                                vSubStage.gitBranch = params.RUN_ON_VERSION?:
                                                                        (vSubStage.git?.gitBranch?:
                                                                            (vMainStage.git?.gitBranch?:
                                                                                (configMap[kSubStage]?.git?.gitBranch?:
                                                                                    (configMap.git?.gitBranch?:
                                                                                        configMap.scm.commit))))
                                                /*gitUrl: url to do git clone*/
                                                vSubStage.gitUrl = vSubStage.git?.gitUrl?:
                                                                        (vMainStage.git?.gitUrl?:
                                                                            (configMap[kSubStage]?.git?.gitUrl?:
                                                                                (configMap.git?.gitUrl?:
                                                                                    configMap.scm.url)))
                                                /*gitCreds: creds to do git clone*/
                                                vSubStage.gitCreds= vSubStage.git?.gitCreds?:
                                                                        (vMainStage.git?.gitCreds?:
                                                                            (configMap[kSubStage]?.git?.gitCreds?:
                                                                                (configMap.git?.gitCreds?:
                                                                                    configMap.scm.creds)))                                                                 
                                            }


                                            /*variantConfig: Each variant in side api call can access variant config here*/
                                            configMap.variantConfig[kSubStage] = vSubStage
                                            commonEnvironment.printConfiguration(vSubStage, "(VARIANT:${kSubStage})")

                                            buildMap[subStageName] = {
                                                timeout(time:vMainStage.timeOut?:(configMap.jobConfig?.timeOut?:configMap.general.timeOut), unit: 'SECONDS'){
                                                    node(vSubStage.runNode){
                                                        //stage(subStageName) {
                                                            script {
                                                                Map apiCallMap = [:]
                                                                (vSubStage.apiCall?:(vMainStage.apiCall?:'gScript'))?.replaceAll('[;,]',' ').tokenize(' ').each{ api ->
                                                                    apiCallMap[[kSubStage,api].join('/')] = {
                                                                        //stage([kSubStage,api].join('/')){
                                                                            try {
                                                                                if (!isMachineSet[kSubStage]){
                                                                                    isMachineSet[kSubStage] = true
                                                                                    manager.createSummary("computer.png").appendText("""<b>Build Machine For ${kSubStage}:</b> 
                                                                                    <a href="${env.JENKINS_URL}/computer/${vSubStage.runNode}">${vSubStage.runNode}</a>
                                                                                    <br><b>Working Directory For ${kSubStage}:</b> ${vSubStage.workingDir}""", false, false, false, "black")
                                                                                }
                                                                                jobUtils.prettyPrint("Stage: ${kMainStage} -|- Variant: ${kSubStage} -|- ApiCall: ${api}.groovy")
                                                                                "${api}" (script: this, stage: kMainStage, variant: kSubStage)
                                                                                configMap.stages[kMainStage].status = 'SUCCESS'
                                                                                configMap.feedbackComposer['CHECK-LIST'].add("\t\t[v] ${kMainStage}".toString())
                                                                            } catch (err) {
                                                                                println(err)
                                                                                configMap.feedbackComposer['CHECK-LIST'].add("\t\t[x] ${kMainStage}/${kSubStage}: ${err.getMessage()}".toString())
                                                                                configMap.verifyScore = -1
                                                                                configMap.stages[kMainStage].status = 'FAILURE'
                                                                                currentBuild.result = 'FAILURE'
                                                                                configMap.emailRecipient = configMap.emailRecipient + ',' + (vSubStage.emailRecipient?:(vMainStage.emailRecipient?:'')).toString()
                                                                                String errMessage = "[ERROR] ${api}: " + err.getMessage()

                                                                                if((err instanceof org.jenkinsci.plugins.workflow.steps.FlowInterruptedException) || (err==null)){
                                                                                    currentBuild.result = 'ABORTED'
                                                                                    configMap.stages[kMainStage].status = 'ABORTED'
                                                                                    errMessage = (err==null)?'[ABORTED] Either User Or Unexpected Reason':'[ABORTED] ' + err.getMessage()
                                                                                    jobUtils.throwPipelineException(errMessage,currentBuild.result)
                                                                                    currentBuild.getRawBuild().getExecutor().interrupt(Result.ABORTED)
                                                                                } else if((err instanceof com.eb.lib.PipelineEnvironmentException)||(err instanceof groovy.lang.MissingPropertyException)){
                                                                                    errMessage = '[ERROR] Pipeline Environment Has Problem ' + err.getMessage()
                                                                                    jobUtils.throwPipelineException(errMessage,currentBuild.result)
                                                                                    currentBuild.getRawBuild().getExecutor().interrupt(Result.FAILURE)
                                                                                } else {
                                                                                    jobUtils.throwRuntimeException(errMessage, currentBuild.result, false)
                                                                                }
                                                                            } finally {
                                                                                if (jobUtils.isFilePath(vSubStage.runNode, vSubStage.workingDir)) {
                                                                                    /*archiveArtifacts: Artifacts to archive*/
                                                                                    def artifactsToArchive = vSubStage.archiveArtifacts?:
                                                                                                                    (vMainStage.archiveArtifacts?:
                                                                                                                        (configMap[kSubStage]?.archiveArtifacts?:
                                                                                                                            (configMap.archiveArtifacts?:
                                                                                                                                configMap.general.archiveArtifacts)))
                                                                                    dir(vSubStage.workingDir){
                                                                                        try{
                                                                                            configMap.rerunFromBuild = [:]
                                                                                            if (!configMap.rerunFromBuild) {
                                                                                                configMap.rerunFromBuild.buildUrl = "${env.BUILD_URL}".toString()
                                                                                                configMap.rerunFromBuild.buildNumber = "${env.BUILD_NUMBER}".toString()
                                                                                            }
                                                                                            
                                                                                            if (!configMap.stages[kMainStage].status.equals('SUCCESS')){
                                                                                                configMap.rerunFromBuild.failedStage = kMainStage.toString()
                                                                                            }
                                                                                            String yamlConfigString = jobUtils.mapToYaml(configMap)
                                                                                            writeFile file: "configMap.yaml", text: yamlConfigString

                                                                                            archiveArtifacts artifacts: "configMap.yaml,${artifactsToArchive}", allowEmptyArchive: true, fingerprint: true
                                                                                        } catch(exc){ println (exc)}
                                                                                    }
                                                                                }
                                                                            }
                                                                        //}
                                                                    }
                                                                }
                                                                apiCallMap.failFast = true
                                                                parallel apiCallMap
                                                            }
                                                        //}
                                                    }
                                                }
                                            }
                                        }
                                        if(vMainStage.parallel) {
                                            buildMap.failFast = vMainStage.failFast?:(configMap.failFast?:false)
                                            parallel buildMap
                                        } else {
                                            buildMap.each{ buildK, buildV ->
                                                buildV()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch(pErr){
        currentBuild.result = 'FAILURE'
        new jobUtils().printStackTrace()
        println(pErr)
    }

}
