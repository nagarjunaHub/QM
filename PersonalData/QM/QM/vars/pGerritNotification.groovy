import com.eb.lib.jobUtils
import com.eb.lib.commonEnvironment

def call(Map parameters) {
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            sh(returnStdout:true, script:"""#!/bin/bash
              source ${new commonEnvironment().loadBashLibs()} && notify_integration_completion_in_gerrit true \
                \$(pwd) ${variantConfig.buildVersion} ${variantConfig.repoReleaseFile} ${env.DEFAULT_GERRIT} \
                ${configMap.pipelineType} ${variantConfig.repoUrl} ${variantConfig.repoBranch} \
                ${variantConfig.aospSharedrive}/changelog_since_last_release.txt ${variantConfig.releaseRoot}/${variantConfig.buildVersion}""")
        }
    }
}