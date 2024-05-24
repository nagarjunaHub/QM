import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime

  if (configRuntime.BUILD_STATUS == "SUCCESS") {
    node(configRuntime.leastloadednode) {
      timestamps {
        def launchersDir = "${configRuntime.workspace}/.launchers"
        if (!fileExists(launchersDir)) {
          dir "${configRuntime.workspace}/.launchers", {
            git branch: "${body.script.buildtools_branch}",
                    url: "${body.script.buildtools_url}"
          }
        } else {
          echo "Directory ${configRuntime.workspace}/.launchers already exists."
        }
      }
      try {
        echo "node name:" + configRuntime.leastloadednode
        echo "Application build is starting for : ${GERRIT_PROJECT}"
        if (configRuntime.pipelineType.contains("verify")) {
          sh(returnStdout: true, script: """#!/bin/bash
                                            source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && app_build ${VERBOSE} ${configRuntime.build_workspace} ${config.dev_env} ${configRuntime.pipelineType} ${GERRIT_BRANCH} "${configRuntime.buildPerDay}" "${configRuntime.ext_build_script}" "${configRuntime.jdk_version}" """)
        } else if (configRuntime.pipelineType.contains("master")) {
          sh(returnStdout: true, script: """#!/bin/bash
                                            source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && app_build ${VERBOSE} ${configRuntime.build_workspace} ${config.dev_env} ${configRuntime.pipelineType} ${GERRIT_BRANCH} "${configRuntime.buildPerDay}" "${configRuntime.ext_build_script}" "${configRuntime.jdk_version}" "${config.branch_identifier}" """)
        }
      } catch(err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = 'FAILURE'
        configRuntime.BUILD_STATUS = 'FAILURE'
        configRuntime.build_addinfo = "(App Build)!!!"
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
      }
    }
  }
}
