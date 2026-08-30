pipeline {
    parameters {
        string(name: 'SERVICE_NAME', defaultValue: 'strategy', description: '项目名/镜像名（必填）')
        string(name: 'IMAGE_TAG', defaultValue: 'dev-20260830', description: '镜像标签（必填）')
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
    image: jenkins/inbound-agent:3309.v27b_9314fd1a_4-1-jdk21
    env:
    - name: HTTP_PROXY
      value: "http://192.168.3.23:7890"
    - name: HTTPS_PROXY
      value: "http://192.168.3.23:7890"
    - name: NO_PROXY
      value: "localhost,127.0.0.1,.svc.cluster.local,10.96.0.0/12,10.244.0.0/16,192.168.109.0/24"
    volumeMounts:
    - name: workspace
      mountPath: /home/jenkins/agent/workspace
  - name: kaniko
    image: gcr.io/kaniko-project/executor:v1.13.0-debug
    command: ["/busybox/sh"]
    args: ["-c", "mkdir -p /usr/bin && ln -sf /busybox/env /usr/bin/env && mount -t proc proc /proc > /dev/null 2>&1 || true && sleep infinity"]
    env:
    - name: HTTP_PROXY
      value: "http://192.168.3.23:7890"
    - name: HTTPS_PROXY
      value: "http://192.168.3.23:7890"
    - name: NO_PROXY
      value: "localhost,127.0.0.1,.svc.cluster.local,10.96.0.0/12,10.244.0.0/16,192.168.109.0/24"
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
    image: maven:3.9-eclipse-temurin-21
    command: ["/bin/sh"]
    args: ["-c", "sleep infinity"]
    env:
    - name: HTTP_PROXY
      value: "http://192.168.3.23:7890"
    - name: HTTPS_PROXY
      value: "http://192.168.3.23:7890"
    - name: NO_PROXY
      value: "localhost,127.0.0.1,.svc.cluster.local,10.96.0.0/12,10.244.0.0/16,192.168.109.0/24"
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
        HARBOR_HOST = 'harbor-release-core.harbor.svc.cluster.local:80'
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
                    echo "✅ Harbor 地址: ${HARBOR_HOST}"
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
            echo "🎉 镜像构建成功: ${HARBOR_HOST}/${SERVICE_NAME}/${SERVICE_NAME}:${IMAGE_TAG}"
        }
        failure {
            echo "❌ 构建失败，请检查日志。"
        }
    }
}