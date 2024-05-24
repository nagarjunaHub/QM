import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config

  def configRuntime = body.script.configRuntime

  if (configRuntime.BUILD_STATUS != "ABORTED") {
    node(configRuntime.leastloadednode) {
      try {
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
        sh(returnStdout: true, script: """#!/bin/bash
                                          source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && cleanup_ws ${VERBOSE} ${configRuntime.pipelineType} ${configRuntime.build_workspace} ${BUILD_NUMBER} """)
      } catch (err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = 'FAILURE'
        configRuntime.build_addinfo = "(CleanUp WS)!!!"
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(), err.toString() + configRuntime.build_addinfo + "%s", configRuntime.email_build_info)
      }
    }
  }
}
