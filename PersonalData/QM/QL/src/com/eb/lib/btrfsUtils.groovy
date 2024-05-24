package com.eb.lib
import com.eb.lib.jobUtils

Boolean isBtrfsSubvolume(String dir) {
    def checkOutput = sh(returnStdout:true, script:"""#!/bin/bash -x
                            sudo btrfs subvolume show ${dir} &>/dev/null
                            echo \$?
                        """).trim().toInteger()
    return (checkOutput>0)?false:true
}

def createSubvolume(String dir) {
    new jobUtils().runShell("btrfs subvolume create ${dir}")
}

def deleteSubvolume(String dir) {
    new jobUtils().runShell("sudo btrfs subvolume delete ${dir}", false)
}

def cloneSubvolume(String srcDir, String dstDir) {
    new jobUtils().runShell("btrfs subvolume snapshot ${srcDir} ${dstDir}")
}

def linkSubvolume(String srcDir, String dstDir) {
    new jobUtils().runShell("""
        pushd \$(dirname ${srcDir}) &>/dev/null
        ln -sfT \$(basename ${srcDir}) ${dstDir}
        popd &>/dev/null
    """)
}

def unlinkSubvolume(String srcDir) {
    new jobUtils().runShell("""unlink ${srcDir}""")
}

/************************************************************************************************************
 * Get subvolume baseline directory for cloning else will create new btrfs directory
 * @param 
 * @return void
**/
def cloneSubvolumeWS (String baselineDir, String dstDir) {
    if(baselineDir.equalsIgnoreCase('NONBTRFS')){
        new jobUtils().runShell("""mkdir -p ${dstDir}""")
    } else {
        if (isBtrfsSubvolume(baselineDir)) {
            cloneSubvolume(baselineDir, dstDir)
        } else {
            createSubvolume(dstDir)
        }
    }
}

/************************************************************************************************************
 * Release current working directory to baseline workspace
 * @param 
 * @return void
**/
def releaseSubvolumeWS (String srcDir, String baselineDir, Boolean alwaysRelease=true) {
    if(!baselineDir.equalsIgnoreCase('NONBTRFS')) {
        if(alwaysRelease){
            if (isBtrfsSubvolume(baselineDir)) {
                deleteSubvolume(baselineDir)
                unlinkSubvolume(baselineDir)
            }
            linkSubvolume(srcDir, baselineDir)
        } else {
            if (!isBtrfsSubvolume(baselineDir)) {
                linkSubvolume(srcDir, baselineDir)
            }
        }
    }
}


/************************************************************************************************************
 * Clean up old workspace if not in use
 * @param 
 * @return void
**/
def cleanUpSubvolumeWS (String workingDir, String baselineDir, Boolean alwaysClean=true) {
    if(baselineDir.equalsIgnoreCase(('NONBTRFS'))){
        new jobUtils().runShell("""rm -rf ${baselineDir}""")
    } else {
        def workingDirBase = workingDir.trim().tokenize('/')[-1]
        def workingDirRoot = workingDir.trim().replaceAll(workingDirBase,'')
        def latestBuildNr = workingDirBase.trim().tokenize('_')[-1]
        def workingDirWithoutBuildNr = workingDirBase.replaceAll(latestBuildNr,'')
        sh(script:"""#!/bin/bash -x
            if [[ "\$(readlink -f ${baselineDir})" == "${workingDir}" ]]; then
                # create sorted list of matching snapshots directories
                workingDirWithSymlink=\$(basename \$(find ${workingDirRoot} -maxdepth 1 -ignore_readdir_race -type l -exec readlink -f {} + | grep "${workingDirWithoutBuildNr}*"))
                for sv in \$(find ${workingDirRoot} -maxdepth 1 -type d -name "${workingDirWithoutBuildNr}*" -printf "%f\n" | sort); do
                    if [[ ! "\${sv}" =~ .*_out.* ]]; then # Exclude out sync folder
                        if [[ "\${sv}" =~ .*@tmp ]]; then
                            rm -rf ${workingDirRoot}/\${sv}
                        else
                            if [ \$(echo \${sv} | awk -F '_' '{ print \$NF }') -lt ${latestBuildNr} ]; then
                                if [[ ! \${workingDirWithSymlink} =~ \${sv} ]]; then
                                    echo "Cleaning Up ${workingDirRoot}/\${sv}"
                                    rm -rf ${workingDirRoot}/\${sv}@tmp || true
                                    sudo btrfs subvolume delete ${workingDirRoot}/\${sv}/out* || true
                                    sudo btrfs subvolume delete ${workingDirRoot}/\${sv} || rm -rf ${workingDirRoot}/\${sv} || true
                                fi
                            fi
                        fi
                    fi
                done
            else
                if [[ ${alwaysClean} == true ]]; then
                    rm -rf ${workingDir}@tmp || true
                    sudo btrfs subvolume delete ${workingDir}/out* || true
                    sudo btrfs subvolume delete ${workingDir} || rm -rf ${workingDir} || true
                fi
            fi
        """)
    }
}