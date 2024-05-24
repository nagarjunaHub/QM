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
        dir "${configRuntime.workspace}/.launchers", {
          git branch: "${body.script.buildtools_branch}",
                  url: "${body.script.buildtools_url}"
        }
      }
      try {
        configRuntime.version = sh(returnStdout: true, script: """#!/bin/bash
                                                                  source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && get_version ${VERBOSE} ${configRuntime.build_workspace} ${configRuntime.prebuilt_app_workspace}""")
        configRuntime.version = configRuntime.version.trim()
        if (configRuntime.version > '90' && FORCE_BUILD == "false") {
          currentBuild.result = 'ABORTED'
          commonlib.__INFO("${GERRIT_PROJECT} is disabled to build as current build number is higher than 90 to save buildnumber for version. To reran job select FORCE_BUILD option==> SKIP!!!")
          currentBuild.description = "SKIPPED: ${GERRIT_PROJECT}"
        } else {
          sh(script: """#!/bin/bash
                        echo ${configRuntime.version} > ${configRuntime.build_workspace}/version.txt
                        echo ${config.branch_identifier} > ${configRuntime.build_workspace}/branch_identifier.txt""")
          echo "App version for this current build: ${configRuntime.version}"
        }
      } catch (err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = 'FAILURE'
        configRuntime.BUILD_STATUS = 'FAILURE'
        configRuntime.build_addinfo = "(Get Version)!!!"
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(), err.toString() + configRuntime.build_addinfo + "%s", configRuntime.email_build_info)
      }
    }
  }
}