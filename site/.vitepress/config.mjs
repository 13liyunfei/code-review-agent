import { defineConfig } from 'vitepress'

const enNav = [
  { text: 'Guide', link: '/guide/' },
  { text: 'Built on agent-kit', link: '/guide/agentkit' },
  { text: 'Architecture', link: '/architecture' },
  { text: 'Console', link: '/guide/console' },
  { text: 'Build', link: '/build' }
]

const zhNav = [
  { text: '指南', link: '/zh/guide/' },
  { text: '基于 agent-kit', link: '/zh/guide/agentkit' },
  { text: '架构', link: '/zh/architecture' },
  { text: '控制台', link: '/zh/guide/console' },
  { text: '构建', link: '/zh/build' }
]

const enSidebar = [
  {
    text: 'Getting started',
    items: [
      { text: 'Introduction', link: '/guide/' },
      { text: 'Quick start', link: '/guide/quickstart' },
      { text: 'Built on agent-kit', link: '/guide/agentkit' }
    ]
  },
  {
    text: 'Core modules',
    items: [
      { text: 'Multi-agent collaboration', link: '/guide/agents' },
      { text: 'Rule engine', link: '/guide/rules' },
      { text: 'SCM integration', link: '/guide/integration' },
      { text: 'Auto-fix', link: '/guide/autofix' },
      { text: 'Human workflow', link: '/guide/workflow' },
      { text: 'RAG knowledge', link: '/guide/rag' }
    ]
  },
  {
    text: 'Advanced',
    items: [
      { text: 'Agentic capabilities', link: '/guide/agentic' },
      { text: 'Observability & security', link: '/guide/observability' },
      { text: 'Management console', link: '/guide/console' }
    ]
  }
]

const zhSidebar = [
  {
    text: '开始',
    items: [
      { text: '简介', link: '/zh/guide/' },
      { text: '快速开始', link: '/zh/guide/quickstart' },
      { text: '基于 agent-kit 构建', link: '/zh/guide/agentkit' }
    ]
  },
  {
    text: '核心模块',
    items: [
      { text: '多 Agent 协同', link: '/zh/guide/agents' },
      { text: '规则引擎', link: '/zh/guide/rules' },
      { text: '代码托管平台接入', link: '/zh/guide/integration' },
      { text: '自动修复', link: '/zh/guide/autofix' },
      { text: '人机协作工作流', link: '/zh/guide/workflow' },
      { text: 'RAG 知识库', link: '/zh/guide/rag' }
    ]
  },
  {
    text: '进阶',
    items: [
      { text: 'Agent 通用能力', link: '/zh/guide/agentic' },
      { text: '可观测与安全', link: '/zh/guide/observability' },
      { text: '管理控制台', link: '/zh/guide/console' }
    ]
  }
]

export default defineConfig({
  base: '/code-review-agent/',
  title: 'code-review-agent',
  description: 'Multi-agent collaborative code review engine — 5 specialized agents, YAML rules, auto-fix, human-in-the-loop workflow, RAG knowledge base',

  locales: {
    root: {
      label: 'English',
      lang: 'en',
      themeConfig: {
        nav: enNav,
        sidebar: enSidebar,
        outlineTitle: 'On this page',
        lastUpdatedText: 'Last updated'
      }
    },
    zh: {
      label: '简体中文',
      lang: 'zh-CN',
      themeConfig: {
        nav: zhNav,
        sidebar: zhSidebar,
        outlineTitle: '本页目录',
        lastUpdatedText: '最后更新'
      }
    }
  },

  themeConfig: {
    search: {
      provider: 'local'
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/13liyunfei/code-review-agent' }
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © 2026 13liyunfei'
    },
    editLink: {
      pattern: 'https://github.com/13liyunfei/code-review-agent/edit/main/site/:path',
      text: 'Edit this page on GitHub'
    }
  },

  markdown: {
    languages: ['java', 'yaml', 'xml', 'bash']
  }
})
