import com.eb.lib.CommonEnvironment

def call(Map parameters) {
  def script = parameters.script
  def stage = parameters.stage
  def variant = parameters.variant
  def configMap = script.configMap
  def stageConfig = configMap.stageConfig
  def variantConfig = configMap.variantConfig[variant]
  def bashLib = new CommonEnvironment().loadBashLibs()

  def release_template = """
  <html>
  <head>
  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
  <style>
  table {
      font-family: arial,sans-serif;
      border-collapse: collapse;
  }

  td, th {
      border: 1px solid #dddddd;
      text-align: left;
      padding: 8px;
  }

  </style>
  </head>
  <body>
  <p>Please find Latest Release information as below:</p>
  <div style=\"overflow-x:auto\">
      <table>
          <tr>
              <th>Component</th>
              <th>Version</th>
              <th>Location</th>
              <th>Status</th>
          </tr>
          %s
      </table>
  </div>
  <br>

  <p><i>** Note: This is automated email by Jenkins</i></p>
  </body>
  </html>
  """
  def table_template = """
      <tr>
          <td>%s</td>
          <td>%s</td>
          <td>%s</td>
          <td>%s</td>
      </tr>
  """

  def release_email_subject = ""

  Date date = new Date()
  def branch_ext = RELEASE_TYPE.toLowerCase().trim().replaceAll(/^(nightly|weekly_snapshots|pi_snapshots)/, "").trim()

  def BRANCH = (branch_ext == "") ? "master" : branch_ext.replace("_","")

  if (! CUSTOMER_RELEASE_BUILD_NAME){
      def w = (date.getAt(Calendar.WEEK_OF_YEAR) - 1 == 0) ? 1 : (date.getAt(Calendar.WEEK_OF_YEAR) - 1)
      def wn = (w < 10) ? "0"+w.toString() : w.toString()
      def dn = (date.getAt(Calendar.DAY_OF_WEEK) - 1).toString()
      def yr = date.format("yy").toString()
      CUSTOMER_RELEASE_BUILD_NAME = "WS_" + wn.toString()
  }

  def tab2space = "  "
  def release_info_builder = ""

  def latest_version = ""
  def table_builder = ""
  def release_email_builder = ""
  def generating_command_builder = []
  def attachmentlist = ""
  def new_package = "False"
  def SEND_FROM = configMap.general.default_email_sender
  def CUSTOMRELEASE = configMap.customRelease
  def release_info_file = configMap.release_all_info_file
  def RELEASE_TYPES = configMap.release_types.trim().tokenize(" ")
  def PACKAGE_RELEASE = configMap.package_release.trim().tokenize(" ")
  def RELEASE_TABLE = [
    "build_name": [
      "release_dir": configMap.release_table.build_name.release_dir + "${RELEASE_TYPE.toLowerCase()}/",
      "version": CUSTOMER_RELEASE_BUILD_NAME.trim(),
      "release_file": configMap.release_table.build_name.release_file
    ],
    "b2_qnx": [
      "release_dir": configMap.release_table.b2_qnx.release_dir,
      "version": B2_QNX_RELEASE_VERSION.trim(),
      "release_file": configMap.release_table.b2_qnx.release_file + ", release_note_${RELEASE_TYPE.toLowerCase()}*.log"
    ],
    "b2_ac": [
      "release_dir": configMap.release_table.b2_ac.release_dir,
      "version": B2_AC_RELEASE_VERSION.trim(),
      "release_file": configMap.release_table.b2_ac.release_file
    ],
    "b2_rse": [
      "release_dir": configMap.release_table.b2_rse.release_dir,
      "version": RSE_AOSP_RELEASE_VERSION.trim(),
      "release_file": configMap.release_table.b2_rse.release_file + ", release_note_${RELEASE_TYPE.toLowerCase()}*.log"
    ]
  ]

  def release_info_template = """
{
  \"%s\": {
%s
  }
}
  """


  def release_info_file_exist = sh(returnStdout:true, script:"""#!/bin/bash -ex
    if [ -f ${release_info_file} ]; then
      echo "True"
    else
      echo "False"
    fi""")
  if (release_info_file_exist == "False") {
    RELEASE_TYPES.each { pr ->
      release_info_builder = (release_info_builder == "") ? ("${tab2space}${tab2space}\"${pr}\": {\n") : ( release_info_builder + ",\n${tab2space}${tab2space}\"${pr}\": {\n")
      release_info_builder = release_info_builder + "${tab2space}${tab2space}${tab2space}\"build_name\": \"\""
      PACKAGE_RELEASE.each { rt ->
        release_info_builder = release_info_builder +  ",\n${tab2space}${tab2space}${tab2space}\"${rt}\": \"\"" 
      }
      release_info_builder = release_info_builder + "${tab2space}${tab2space}\n}"
    }
    release_info_template = String.format(release_info_template,BRANCH,release_info_builder)
    writeFile file: release_info_file, text: release_info_template
    writeFile file: release_info_file+"_prev", text: release_info_template
  }

  try {
    PACKAGE_RELEASE.each {
      stage("Generating: ${it}") {
        if (! RELEASE_TABLE[it]["version"]) {
          def release_dir = RELEASE_TABLE[it]['release_dir']
          RELEASE_TABLE[it]["version"] = sh(returnStdout:true, script:"""#!/bin/bash -e
              source ${bashLib} && get_latest_release_version ${release_dir}""").trim()
        }

        latest_version = RELEASE_TABLE[it]["version"]
        def package_status = sh(returnStdout:true, script:"""#!/bin/bash -ex
          source ${bashLib} && prod_get_release_status ${release_info_file} ${BRANCH} ${RELEASE_TYPE} ${it} ${latest_version}""").trim()

        if (package_status == "New"){
          sh("""#!/bin/bash -ex
            source ${bashLib} && prod_update_release_info ${release_info_file} ${BRANCH} ${RELEASE_TYPE} ${it} ${latest_version} ${RELEASE_TABLE[it]['release_dir']}""")
          new_package = "True"
        }

        if (RELEASE_TABLE[it]["release_file"] != "") {
          RELEASE_TABLE[it]["release_file"].trim().tokenize(",").each() { rf ->
            generating_command_builder = generating_command_builder + [ it, RELEASE_TABLE[it]["version"], RELEASE_TABLE[it]['release_dir'], rf.trim() ]
          }
        }

        table_builder = table_builder + String.format(table_template, it, RELEASE_TABLE[it]["version"], RELEASE_TABLE[it]['release_dir'] + RELEASE_TABLE[it]["version"], package_status)

        def release_note_dir = RELEASE_TABLE[it]['release_dir'] + RELEASE_TABLE[it]["version"]
        def attach_file = sh(returnStdout:true, script:"""#!/bin/bash -ex
          [ -f ${WORKSPACE}/${it.toUpperCase()}_${RELEASE_TABLE[it]["version"]}_release_note_${RELEASE_TYPE}.log ] && rm -rf ${WORKSPACE}/${it.toUpperCase()}_${RELEASE_TABLE[it]["version"]}_release_note_${RELEASE_TYPE}.log
          [ -d ${release_note_dir} ] && find ${release_note_dir} -name "release_note_${RELEASE_TYPE}*.log" -type f -exec cp -rf {} ${WORKSPACE}/${it.toUpperCase()}_${RELEASE_TABLE[it]["version"]}_release_note_${RELEASE_TYPE}.log \\;
          [ -f ${WORKSPACE}/${it.toUpperCase()}_${RELEASE_TABLE[it]["version"]}_release_note_${RELEASE_TYPE}.log ] && echo ${WORKSPACE}/${it.toUpperCase()}_${RELEASE_TABLE[it]["version"]}_release_note_${RELEASE_TYPE}.log || echo  """).trim()
        if (attach_file){
          attachmentlist = "${attach_file} ${attachmentlist}".trim()
        }
      }
    }

    if (RELEASE_TYPE.toLowerCase().contains("nightly")){
      CUSTOMER_RELEASE_BUILD_NAME = date.format("yyyy-MM-dd") + "_" + date.format("HHmm")
      release_email_subject = "[${CUSTOMRELEASE.toUpperCase()}][${RELEASE_TYPE.toUpperCase()}] New Release: ${CUSTOMER_RELEASE_BUILD_NAME} Is Available!"
      currentBuild.description = "${RELEASE_TYPE.toUpperCase()}:" + CUSTOMER_RELEASE_BUILD_NAME
    } else {
      release_email_subject = "[${CUSTOMRELEASE.toUpperCase()}][${RELEASE_TYPE.toUpperCase()}] New Release: ${CUSTOMER_RELEASE_BUILD_NAME} Is Available!"
      currentBuild.description = "${RELEASE_TYPE.toUpperCase()}:" + CUSTOMER_RELEASE_BUILD_NAME
      def gen_cmd = generating_command_builder.join(" ").trim()
      sh """#!/bin/bash -e
        source ${bashLib} && prod_release_all_package_generator ${PACKAGE_RELEASE_CREDENTIAL} ${RELEASE_TABLE["build_name"]["release_dir"]} ${RELEASE_TABLE["build_name"]["version"]} ${gen_cmd}"""
    }
    if (new_package == "False"){
      release_email_subject = "[${CUSTOMRELEASE.toUpperCase()}][${RELEASE_TYPE.toUpperCase()}] No New Release Due To No Changes!"
    }
  } catch(err) {
    echo err.toString()
    currentBuild.result = "FAILURE"
    sh("""#!/bin/bash -ex
      rm -rf ${release_info_file}_tmp ${release_info_file}_prev_tmp ${WORKSPACE}/*release_note*.log""")
  }

  stage("Releasing") {
    if (currentBuild.result != "FAILURE") {
      sh("""#!/bin/bash -ex
        [ -f ${release_info_file}_prev_tmp ] && mv -f ${release_info_file}_prev_tmp ${release_info_file}_prev || true
        [ -f ${release_info_file}_tmp ] && mv -f ${release_info_file}_tmp ${release_info_file} || true""")
      release_email_builder = String.format(release_template, table_builder)
      if (SEND_TO != ""){
        sh("""#!/bin/bash -e
          source ${bashLib} && eb_mail --subject \"${release_email_subject}\" --from ${SEND_FROM} --to ${SEND_TO.replaceAll("\n",",")} --mime \"text/html\" --body \"${release_email_builder}\" --attachments \"${attachmentlist}\"""")
      }
      sh("rm -rf ${WORKSPACE}/*release_note*.log")
    }
  }
}
