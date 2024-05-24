def getBashLib() {
return '''
dev_bash_lib_demo(){
    local demostring="hello\\this\\is\\demo"
    echo "$(super_standardize_string ${demostring})"
}
'''
}