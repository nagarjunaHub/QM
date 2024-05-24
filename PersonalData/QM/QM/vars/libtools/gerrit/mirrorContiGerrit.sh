#!/bin/bash
WORKSPACE=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
list_links=""
PS3="Please choose service: "
options=("geturl" "mirror" "update" "quit")
select opt in "${options[@]}"
do
    case $opt in
        "geturl")
            echo "You chose: $opt"
            list_search_hosts="conti.de OIP_GIT_SERVER"
            rm -rf "${WORKSPACE}/list_links" && touch "${WORKSPACE}/list_links"
            for host in ${list_search_hosts[@]}; do 
                list_links="$(grep -riEh "^SRC_URI.*${host}" meta-* | awk '{ print $3 }' | tr -d '\"' | cut -d";" -f1) ${list_links}"
            done

            for link in ${list_links[@]}; do
                link=${link/gitsm:\/\//ssh:\/\/}
                link=${link/git:\/\//ssh:\/\/}
                link=${link/\$\{OIP_GIT_SERVER\}/ssh:\/\/wetp715x.we.de.conti.de:29418}
                link=${link/\$\{OIP_GIT_PARAMS\}/}
                link=${link/\$\{BPN\}/build-info}
                echo ${link} >> "${WORKSPACE}/list_links"
                fd_link=$(dirname ${link})
                fd_link=${fd_link/ssh:\/\/*.conti.de:29418\//}
                fd_link=${fd_link/ssh:\/\/*.conti.de\//}
            done
            break
            ;;
        "mirror")
            echo "You chose: $opt"
            while IFS= read -r link || [[ -n "$link" ]]; do
                fd_link=$(dirname ${link})
                fd_link=${fd_link/ssh:\/\/*.conti.de:29418\//}
                fd_link=${fd_link/ssh:\/\/*.conti.de\//}
                mkdir -p ${fd_link} && pushd ${fd_link} &>/dev/null
                git clone --mirror ${link} || true
                popd &>/dev/null
            done < "${WORKSPACE}/list_links"
            for dir in $(find ${WORKSPACE} -type d -name "*.git" -not -path "*/.repo/*"); do 
                pushd ${dir} &>/dev/null
                git config uploadpack.allowReachableSHA1InWant true
                popd &>/dev/null
            done
            break
            ;;
        "update")
            echo "You chose: $opt"
            for dir in $(find . -type d -name "*.git" -not -path "*/.repo/*"); do
                pushd ${dir} &>/dev/null
                git remote update
                git lfs install
                git lfs fetch --all
                popd &>/dev/null
            done
            break
            ;;
        "quit")
            echo "Quit..."
            exit 0
            ;;
        *) echo "invalid option $REPLY";;
    esac
done