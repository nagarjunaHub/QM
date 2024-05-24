import com.eb.lib.jobUtils
import com.eb.lib.btrfsUtils

def call(Map parameters) {
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    Boolean alwaysRelease = (variantConfig.alwaysRelease!=null)?variantConfig.alwaysRelease:
                                ((stageConfig.alwaysRelease!=null)?stageConfig.alwaysRelease:true)
    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            if(variantConfig.runScript){
                new jobUtils().scriptRun(variantConfig.runScript, variantConfig.scriptNamePrefix)
            }
        }
    }

    new btrfsUtils().releaseSubvolumeWS(variantConfig.workingDir, variantConfig.subvolumeBaselineDir, alwaysRelease)
}