import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {

    // For pipeline common config
    def commonlib = new aospUtils()
    def config = body.script.config

    def configRuntime = body.script.configRuntime
    def target_id_list = configRuntime.supported_targets.tokenize(" ")

    def aospOTMParallelMap = [:]

    node(configRuntime.pipeline_node) {
        target_id_list.each { target_id ->
            configRuntime.build_type_list = config[configRuntime.pipelineType][target_id]["build_type_list"].tokenize(" ")
            configRuntime.build_type_list.each { build_type ->
                if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
                    commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
                    return
                }
                make_targets = String.format("%s+%s+%s", "droid", config["supported_target_ids"][target_id]["custom_env_vars"], config[configRuntime.pipelineType].pipeline_make_targets)
                user_custom_build_env = config["supported_target_ids"][target_id]["user_custom_build_env"]
                lunch_target = String.format(config["supported_target_ids"][target_id]["lunch_target_template"], build_type)

                stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
                stackTargetFile = config.stacktarget_file
                build_target = [target_id,build_type].join("-")

                // Only for user builds.
                if (!(build_type.contains("userdebug"))) {
                    OTM_Test_volume = configRuntime.build_variant[build_target].source_volume + "_OTM_Test"

                    OTM_Test_src_volume = configRuntime.build_variant[build_target].source_volume

                    OTM_Test_job_parameters = [
                            'DOCKER_IMAGE_ID=' +      config.docker_image_id,
                            'LUNCH_TARGET=' +         lunch_target,
                            'SNAPSHOT_NAME=' +        configRuntime.project_release_version,
                            'STACK_TARGET_LABEL=' +   configRuntime.build_variant[build_target].least_loaded_node,
                            "CAUSED_BY=" +            [JOB_NAME, BUILD_NUMBER].join("/"),
                            'OTM_TEST_SOURCE_VOLUME='+   OTM_Test_volume,
                    ]
                    OTM_parameters = [
                            'LEAST_LOADED_NODE=' +   configRuntime.build_variant[build_target].least_loaded_node,
                            'TARGET_VOLUME=' +       OTM_Test_volume,
                            'SRC_VOLUME=' +          OTM_Test_src_volume,
                            'DOWNSTREAM_JOB=' +      config.OTM_freestyle_job,
                            'BUILD_TARGET=' +        build_target,
                    ]
                    aospOTMParallelMap[build_target] = commonlib.configureDownstreamBuild(OTM_parameters, OTM_Test_job_parameters)
                }
            }
        }
    }


    try {
        parallel aospOTMParallelMap

    } catch(err) {
        commonlib.__INFO(err.toString())
        // We ignore failure from sonarqube stage!
        configRuntime.build_addinfo = "(OTM TEST Snapshot failed)!!!"
    }

}
