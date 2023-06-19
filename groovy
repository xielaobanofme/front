pipeline {
  agent {
    label "master"
  }
  parameters {
    string(name:'PROJECT', defaultValue: env.PROJECT, description:'[必填]选择构建哪个项目,如:\nfront_toc, front_tob\n注：4.2及以上版本含consumer')
    choice(name: 'ACTION', choices: ['build','start', 'restart','clear_all'], description: 'build,restart,clear_all')
    choice(name: 'YAMR_INSTALL', choices: ['false', 'true'], description:'[可选] 是否启用前端的yarm install,默认不需要，前端需要新增模块时才会true')
    }
  environment {


    PIPELINE_SCRIPT_PATH = "jenkinsfiles"
//    NETWORK = "multi_${params.VER}"

    Credentials = "c2b99023-0a1c-4984-a3aa-39432bbc7ff7"
    // 容器启动失败重试次数
    DOCKER_RETRY_NUM = 2

  }

  options {
    // 不允许并发构建
    // disableConcurrentBuilds()
    // 控制台日志显示时间
    timestamps()
    // Discard old builds
    copyArtifactPermission("${JOB_BASE_NAME}");
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '10', numToKeepStr: '20')
  }

  stages {
    stage('初始化') {
      steps {
        script {
          // 从attribute.json文件中读取参数
          def props = readJSON file: "${PIPELINE_SCRIPT_PATH}/attribute_ci_compent.json"
          //oder和fronttoc是一个容器不同目录，同时git地址又不同，所以分开两个项目启动一个容器

            echo"***********front_toc/tob/manage/websit/applets/mobile***********${params.VER}___${params.PROJECT}"

           env.CONTAINER_NAME = "${params.VER}_${params.PROJECT}"
            env.PORT = props["${params.VER}"]["${params.PROJECT}"]['port']
            env.REPOURL = props["${params.VER}"]["${params.PROJECT}"]['git']
            env.BRANCH = props["${params.VER}"]["${params.PROJECT}"]['branch']
            env.PROJECT_PATH = "${PROJECT_BASE}/${params.PROJECT}"
          env.SUBNET = props["${params.VER}"]["${params.PROJECT}"]['subnet']
          env.NETWORK = props["${params.VER}"]["${params.PROJECT}"]['network']
//          env.SUBNET = props["${params.VER}"]['subnet']
//          env.NETWORK = props["${params.VER}"]['network']



          // 控制台输出
          echo "port====: ${env.PORT}\'"
          // 初始化当前构建名称,构建完成后会被覆盖
          currentBuild.displayName = "#${BUILD_NUMBER}_${params.PROJECT}_(${params.ACTION})"
          // 定义当前build描述,admin:${env.ADMIN_PORT}, api:${env.API_PORT}, es:${env.ES_HTTP_PORT}, db:${env.MYSQL_DB},svn:${env.REPOURL}
          currentBuild.description = "${params.PROJECT}:${env.PORT}，分支：${env.BRANCH}"
        }
      }
      post {
        success {
          node("${params.DockerLabel}") {
            script {
              // clear_all操作无需创建env.NETWORK
              sh script: "if [ ${params.ACTION} != 'clear_all' ]; then docker network ls | grep -w ${env.NETWORK} 2>/dev/null || docker network create --driver overlay --subnet=${env.SUBNET} ${env.NETWORK}; fi", label:'创建overlay network'
              // 根据目录时间排序获取上一版本号,并赋给当前版本号变量env.VERSION,如:1.0-SNAPSHOT
              env.VERSION = sh (script: "ls -t ${env.PROJECT_PATH} | head -1", returnStdout: true, label: 'LAST_VERSION').trim()


            }
          }
        }
      }
    }
    stage('编译') {
      when {
        beforeAgent true
        expression { params.ACTION == 'build' }
      }
      steps {
        ws("/jenkins-workspace/workspace/${JOB_NAME}/${params.PROJECT}") {
          echo "**********REPOURL: ${env.REPOURL}"

          script {

            checkout([$class: 'GitSCM', branches: [[name: "${env.BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: "${Credentials}", url: "${env.REPOURL}"]]])
            if ("${params.YAMR_INSTALL}" =='true'){
              echo "**********yarm install start*********"
              sh script: """
                    yarn install
                    """,label:"yarn install"
            }
            echo "**********yarm build start*********"
            sh script: """
                    yarn add @ant-design/pro-table@2.54.0
                    yarn build
                    tar zcf build.tar.gz build
                  """, label: "yarn build"
            archiveArtifacts artifacts: "build.tar.gz", onlyIfSuccessful: false, fingerprint: true, allowEmptyArchive: true

        }
        }
      }
      post {
        success {
          node("${params.DockerLabel}") {
            // 指定ws，解决并发构建时分配的WORKSPACE为xx@2导致后面步骤找不到jar问题
            ws("/data/jenkins_slave/workspace/${JOB_NAME}/${params.PROJECT}") {
              // 拷贝所有本次编译已归档的文件到当前node指定的WORKSPACE下的Artifacts_${BUILD_NUMBER}临时目录,会自动创建该目录
              copyArtifacts flatten: true, projectName: "${JOB_BASE_NAME}", selector: specific("${BUILD_NUMBER}"), target: "Artifacts_${BUILD_NUMBER}"
            }
          }
        }
      }

    }
    stage('拷贝文件_FRONT') {
      agent {
        label "${params.DockerLabel}"
      }
      when {
        beforeAgent true
        expression { params.ACTION == 'build' }
      }
      options {
        timeout(time: 5, unit: 'MINUTES')
      }

      steps {

        script {

            // 在指定的WORKSPACE下操作
            if(params.PROJECT=='front_order'){
              echo"***********拷贝front_order里文件到项目中***********==~${params.PROJECT}"
              ws("/data/jenkins_slave/workspace/${JOB_NAME}/${params.PROJECT}") {
                // 加反斜杠 \cp 执行cp命令时不走alias,-f 使强制覆盖
                sh script: """
                [ ${env.PROJECT_BASE}/front_toc/front_order ] && rm -rf ${env.PROJECT_BASE}/front_toc/front_order/*;
                
                \\cp -f Artifacts_${BUILD_NUMBER}/build.tar.gz ${PROJECT_BASE}/front_toc/front_order
                cd ${PROJECT_BASE}/front_toc/front_order && tar zxf build.tar.gz
              """,label: "拷贝build包到front_toc目标路径"
                sh script: """
                [ ${env.PROJECT_BASE}/front_mobile/front_order ] && rm -rf ${env.PROJECT_BASE}/front_mobile/front_order/*;
                
                \\cp -f Artifacts_${BUILD_NUMBER}/build.tar.gz ${PROJECT_BASE}/front_mobile/front_order
                cd ${PROJECT_BASE}/front_mobile/front_order && tar zxf build.tar.gz
              """,label: "拷贝build包到front_mobile目标路径"
                sh "rm -rf Artifacts_${BUILD_NUMBER}*"

              }
            }else{
              ws("/data/jenkins_slave/workspace/${JOB_NAME}/${params.PROJECT}") {
                echo"***********拷贝文件_FRONT  非front_order项目***********==~${params.PROJECT}"
                // 加反斜杠 \cp 执行cp命令时不走alias,-f 使强制覆盖
                sh script: """
                [ ${env.PROJECT_PATH}/${params.PROJECT} ] && rm -rf ${env.PROJECT_PATH}/${params.PROJECT}/*;
                
                \\cp -f Artifacts_${BUILD_NUMBER}/build.tar.gz ${env.PROJECT_PATH}/${params.PROJECT}
                cd ${env.PROJECT_PATH}/${params.PROJECT} && tar zxf build.tar.gz
              """,label: "拷贝build包到目标路径"
                sh "rm -rf Artifacts_${BUILD_NUMBER}*"
              }
            }


        }


      }
    }
    stage('启动容器_FRONT') {
      agent {
        label "${params.DockerLabel}"
      }
      when {
        beforeAgent true
        expression { params.ACTION != 'clear_all'  }
        expression { params.PROJECT != 'front_order' }
      }
      options {
        timeout(time: 5, unit: 'MINUTES')
      }

      steps {

        script {
          echo"***********PROJECT***********==~${params.PROJECT}"

            FRONT_CONFIG_PATH = "${WORKSPACE}/${PIPELINE_SCRIPT_PATH}/conf_template/${params.VER}/nginx_${params.PROJECT}.conf"

        }
        startSteps(FRONT_RUN,env.CONTAINER_NAME)  //启动容器

      }
    }
    stage('删除容器_front') {
      agent {
        label "${params.DockerLabel}"
      }
      when {
        beforeAgent true
        expression { params.ACTION == 'clear_all' }
        expression { params.PROJECT != 'front_order' }
      }
      options {
        timeout(time: 5, unit: 'MINUTES')
      }

      steps {
        // 初始化环境变量
        clear()
      }
    }

  }
  post {
    always {
      script {
        currentBuild.displayName = "#${BUILD_NUMBER}_${params.PROJECT}_${params.ACTION}"
      }
    }
  }
}
}
def genProperties() {
  // 替换文件中所有${}标记的变量为实际的值并生成新的配置文件,覆盖项目下的同名文件
  // 配置文件有:
  sh script: """  
    envsubst < ${CONF_TEMP_PATH} > ${CONFIG_PATH}
  """,label: "生成auth properties"
}
def genProperties_cloud() {
  // 替换文件中所有${}标记的变量为实际的值并生成新的配置文件,覆盖项目下的同名文件
  // 配置文件有:
  sh script: """  
    envsubst < ${CLOUD_TEMP_PATH} > ${CLOUD_CONFIG_PATH}
  """,label: "生成auth properties"
}

// 清理环境
def clear() {
  // 删除该overlay网络下的所有容器
  // 断开所有容器与该网络的连接
  // 删除该overlay网络
  // 清理未使用的镜像,如农发行每次构建产生的镜像
  sh script: """
    docker stop \$(docker ps -aq -f "name=${CONTAINER_NAME}" -f "label=${CONTAINER_NAME}") || echo "Stoped."
    docker rm -f \$(docker ps -aq -f "name=${CONTAINER_NAME}" -f "label=${CONTAINER_NAME}") || echo "Cleaned up."
    docker network ls | grep -w ${env.NETWORK} 2>/dev/null && container_list=`docker network inspect --format='{{range .Containers}}{{.Name}} {{end}}' ${env.NETWORK}` || true
    for container in \${container_list}; do
       docker network disconnect -f ${env.NETWORK} \${container} 2>/dev/null || true
    done
    docker network ls | grep -w ${env.NETWORK} 2>/dev/null && docker network rm ${env.NETWORK} || true
    docker image prune -f
  """,label:"删除容器及网络",returnStatus: true
}

