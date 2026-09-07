pipeline {
    parameters {
        string(name: 'SERVICE_NAME', defaultValue: 'strategy', description: '项目名/镜像名（必填）')
        string(name: 'IMAGE_TAG', defaultValue: 'latest', description: '镜像标签（必填，默认 latest，ArgoCD 自动收敛）')
    }

    agent {
        kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
metadata:
  annotations:
    jenkinsci.org/durable-task-launch-diagnostics: "true"
spec:
  containers:
  - name: jnlp
    image: 192.168.254.130:32100/library/jenkins-inbound-agent:3309.v27b_9314fd1a_4-1-jdk21
    volumeMounts:
    - name: workspace
      mountPath: /home/jenkins/agent/workspace
  - name: kaniko
    image: 192.168.254.130:32100/library/kaniko-project-executor:v1.13.0-debug
    command: ["/busybox/sh"]
    args: ["-c", "mkdir -p /usr/bin && ln -sf /busybox/env /usr/bin/env && mount -t proc proc /proc > /dev/null 2>&1 || true && sleep infinity"]
    volumeMounts:
    - name: docker-config
      mountPath: /kaniko/.docker/config.json
      subPath: config.json
    - name: workspace
      mountPath: /home/jenkins/agent/workspace
    - name: tmp
      mountPath: /tmp
    - name: proc
      mountPath: /proc
    resources:
      requests:
        cpu: "500m"
        memory: "1Gi"
      limits:
        cpu: "2"
        memory: "2Gi"
    securityContext:
      runAsUser: 0
      runAsGroup: 0
    tty: true
  - name: maven
    image: 192.168.254.130:32100/library/maven:3.9-eclipse-temurin-21
    command: ["/bin/sh"]
    args: ["-c", "sleep infinity"]
    volumeMounts:
    - name: workspace
      mountPath: /home/jenkins/agent/workspace
    resources:
      requests:
        cpu: "500m"
        memory: "1Gi"
      limits:
        cpu: "2"
        memory: "2Gi"
  volumes:
  - name: docker-config
    secret:
      secretName: harbor-robot-cred
      items:
      - key: .dockerconfigjson
        path: config.json
  - name: workspace
    emptyDir: {}
  - name: tmp
    emptyDir: {}
  - name: proc
    hostPath:
      path: /proc
'''
        }
    }

    environment {
        HARBOR_HOST = 'harbor.cicd.svc:80'
    }

    stages {
        stage('Init') {
            steps {
                script {
                    env.SERVICE_NAME = params.SERVICE_NAME ?: ''
                    env.IMAGE_TAG = params.IMAGE_TAG ?: ''
                    if (!env.SERVICE_NAME || !env.IMAGE_TAG) {
                        error "❌ 缺少必填参数！"
                    }
                    echo "✅ 项目名/镜像名: ${env.SERVICE_NAME}"
                    echo "✅ 镜像标签: ${env.IMAGE_TAG}"
                    echo "✅ Harbor 地址: http://${HARBOR_HOST}"
                }
            }
        }

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Maven Compile') {
            when {
                expression { env.BRANCH_NAME in ['master', 'dev', 'test', 'AI'] }
            }
            steps {
                container('maven') {
                    sh '''
                        # 使用仓库自带 settings.xml（阿里云镜像加速）
                        # 仅编译级校验，快速失败；完整打包在 Dockerfile builder 阶段执行
                        mvn -B -s maven-settings.xml clean compile
                    '''
                }
            }
        }

        stage('Build and Push Image') {
            when {
                expression { env.BRANCH_NAME in ['master', 'dev', 'test', 'AI'] }
            }
            steps {
                container('kaniko') {
                    sh """
                        /kaniko/executor \
                            --context=. \
                            --dockerfile=Dockerfile \
                            --destination=${HARBOR_HOST}/${SERVICE_NAME}/${SERVICE_NAME}:${IMAGE_TAG} \
                            --cache=true \
                            --insecure \
                            --insecure-registry=${HARBOR_HOST} \
                            --skip-tls-verify \
                            --verbosity=debug
                    """
                }
            }
        }
    }

    post {
        always {
            echo "构建分支: ${env.BRANCH_NAME}"
            echo "项目名/镜像名: ${env.SERVICE_NAME}"
            echo "镜像标签: ${env.IMAGE_TAG}"
        }
        success {
            echo "🎉 镜像构建成功: http://${HARBOR_HOST}/${SERVICE_NAME}/${SERVICE_NAME}:${IMAGE_TAG}"
        }
        failure {
            echo "❌ 构建失败，请检查日志。"
        }
    }
}