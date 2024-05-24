import com.eb.lib.jobUtils
import com.eb.lib.buildTrigger

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
            if (configMap.env.GERRIT_PROJECT?.contains("AOSP-manifest-release")){
                if (variantConfig.snapshotJob?.trim() && (currentBuild.result == 'SUCCESS')){
                    def params = [new StringParameterValue('CAUSED_BY', env.BUILD_URL)]
                        new buildTrigger().
                            build(job: variantConfig.snapshotJob,
                                wait: false,
                                propagate: false,
                                parameters: params)
                }
            }
        }
    }
}