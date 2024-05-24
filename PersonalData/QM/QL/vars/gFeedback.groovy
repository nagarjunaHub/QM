import com.eb.lib.jobUtils
import com.eb.lib.gerritUtils

def call(Map parameters) {
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    def feedbackComposerTxt = "•> INFO:\n\t\t- Checked Variants: ${configMap.variants.join(", ").toString()}"
    feedbackComposerTxt += configMap.feedbackComposer['INFO']?configMap.feedbackComposer['INFO'].unique().join('\n-').toString():''
    feedbackComposerTxt += configMap.feedbackComposer['CHECK-LIST']?
                            "\n\n•> CHECK-LIST: \n" + configMap.feedbackComposer['CHECK-LIST'].unique().join('\n').toString():''
    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            if(variantConfig.runScript){
                new jobUtils().scriptRun(variantConfig.runScript, variantConfig.scriptNamePrefix)
            }
            def emailRecipient = configMap.emailRecipient.tokenize(',')
            if (configMap.isChangeTriggered) {
                emailRecipient += new gerritUtils().getReviewersEmail(configMap.env.GERRIT_CHANGE_NUMBER)
                emailRecipient += [ configMap.env.GERRIT_EVENT_ACCOUNT_EMAIL ]
                feedbackComposerTxt += "\n\n•> LOGS: \n\t\t- Parsed-Log: ${env.BUILD_URL}parsed_console\n\t\t- Full-Log: ${env.BUILD_URL}consoleFull".toString()
                if(configMap.isGetFlash){
                    feedbackComposerTxt += "\n\n•> BINARY (48Hrs): ${configMap.releaseRootDir}/get_flash/${variantConfig.buildVersion}".toString()
                    new gerritUtils().gerritPostComment(
                            configMap.env.GERRIT_CHANGE_NUMBER,
                                configMap.env.GERRIT_PATCHSET_NUMBER,
                                    feedbackComposerTxt)
                } else {
                    if (configMap.verifyScore < 0){
                        feedbackComposerTxt += "\n\n•> VERDICT: FAILED".toString()
                        feedbackComposerTxt += "\n\n•> RETRIGGER: ${env.BUILD_URL}gerrit-trigger-retrigger-this".toString()
                    } else {
                        feedbackComposerTxt += "\n\n•> VERDICT: PASSED".toString()
                    }
                    new gerritUtils().gerritSetReview(
                            configMap.env.GERRIT_CHANGE_NUMBER,
                                configMap.env.GERRIT_PATCHSET_NUMBER,
                                    feedbackComposerTxt, configMap.verifyScore)
                }
                feedbackComposerTxt += "\n\n•> COMMIT: ${configMap.env.GERRIT_CHANGE_URL}".toString()
            } else {
                feedbackComposerTxt += "\n\n•> LOGS: \n\t\t- Parsed-Log: ${env.BUILD_URL}parsed_console\n\t\t- Full-Log: ${env.BUILD_URL}consoleFull".toString()
            }

            /* Parser output has to be the last step so we can catch problems with metics and builds */
            String errorParsingRuleString = libraryResource variantConfig.errorParsingRule?:'eb/global/parsing/common'
            writeFile file: "${env.WORKSPACE}/errorParsingRule", text: errorParsingRuleString
            step([$class: 'LogParserPublisher', failBuildOnError: false, projectRulePath: "${env.WORKSPACE}/errorParsingRule", unstableOnWarning: false, useProjectRule: true])

            if (!currentBuild.result.equalsIgnoreCase('SUCCESS')){
                def emailSubject = "${currentBuild.result}: ${env.JOB_NAME}#${env.BUILD_NUMBER}"
                if(configMap.rerunFromBuild?.buildNumber){
                    emailSubject = "${currentBuild.result}: ${env.JOB_NAME}#${env.BUILD_NUMBER} (Rerun From Build: ${configMap.rerunFromBuild?.buildNumber})"
                }
                emailext(body: feedbackComposerTxt,
                    to: emailRecipient.unique().join(',').replaceAll(",null","").trim(),
                    from: configMap.general.email.defaultEmailSender,
                    subject: "${currentBuild.result}: ${env.JOB_NAME}#${env.BUILD_NUMBER}")
            }
        }
    }
}