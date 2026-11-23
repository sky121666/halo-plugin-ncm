/**
 * 默认编辑器扩展：网易云音乐卡片（歌曲 / 歌单 / 专辑）。
 * 通过 Halo 的 default:editor:extension:create 扩展点注册（见 ui/src/index.ts），
 * 写法参考 Halo 2.27 自带的 iframe 扩展（ui/packages/editor/src/extensions/iframe）。
 *
 * 存进文章的 HTML 不依赖插件也能看（没有 JS、RSS、插件被卸载时就是一个普通链接）：
 *   <div class="ncm-card" data-ncm-card="" data-type="song" data-id="186016">
 *     <a href="https://music.163.com/#/song?id=186016">晴天</a>
 *   </div>
 * 前台由插件的 static/card.js 渲染成卡片。
 */
import {
  BlockActionSeparator,
  Node,
  PluginKey,
  ToolboxItem,
  VueNodeViewRenderer,
  deleteNode,
  isActive,
  type Editor,
  type EditorState,
  type ExtensionOptions,
  type NodeBubbleMenuType,
  type Range,
} from '@halo-dev/richtext-editor'
import { markRaw } from 'vue'
import RiDeleteBinLine from '~icons/ri/delete-bin-line'
import RiEditLine from '~icons/ri/edit-line'
import RiExternalLinkLine from '~icons/ri/external-link-line'
import RiNeteaseCloudMusicLine from '~icons/ri/netease-cloud-music-line'
import NcmCardView from './NcmCardView.vue'
import { CARD_EDIT_EVENT, NCM_CARD_NODE } from './constants'
import { canonicalUrl, defaultTitle, isValidId, normalizeType } from './link'

export const NCM_CARD_BUBBLE_MENU_KEY = new PluginKey('ncmCardBubbleMenu')

const TITLE = '网易云音乐卡片'

function insertCard(editor: Editor, range?: Range) {
  const chain = editor.chain().focus()
  if (range) chain.deleteRange(range)
  chain.insertContent({ type: NCM_CARD_NODE, attrs: { ncmType: 'song', ncmId: '', title: '' } }).run()
}

/** 通知当前选中的卡片打开修改弹窗（弹窗在节点视图里）。 */
function openEditor(editor: Editor) {
  const dom = editor.view.nodeDOM(editor.state.selection.from) as HTMLElement | null
  if (!dom || typeof dom.dispatchEvent !== 'function') return
  const target =
    dom.matches?.('[data-node-view-wrapper]') ? dom : (dom.querySelector?.('[data-node-view-wrapper]') as HTMLElement | null) || dom
  target.dispatchEvent(new CustomEvent(CARD_EDIT_EVENT))
}

export const ExtensionNcmCard = Node.create<ExtensionOptions>({
  name: NCM_CARD_NODE,
  group: 'block',
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      ncmType: {
        default: 'song',
        parseHTML: (el: HTMLElement) => normalizeType(el.getAttribute('data-type')),
        renderHTML: () => ({}),
      },
      ncmId: {
        default: '',
        parseHTML: (el: HTMLElement) => {
          const id = (el.getAttribute('data-id') || '').trim()
          return isValidId(id) ? id : ''
        },
        renderHTML: () => ({}),
      },
      title: {
        default: '',
        parseHTML: (el: HTMLElement) => (el.querySelector('a')?.textContent || '').trim(),
        renderHTML: () => ({}),
      },
    }
  },

  parseHTML() {
    return [
      {
        tag: 'div[data-ncm-card]',
        // 比普通 div / 段落优先；data-id 不合法时不当成卡片，按普通内容解析
        priority: 100,
        getAttrs: (dom: HTMLElement) => (isValidId((dom.getAttribute('data-id') || '').trim()) ? {} : false),
      },
    ]
  },

  renderHTML({ node }) {
    const type = normalizeType(node.attrs.ncmType)
    const id = isValidId(node.attrs.ncmId) ? (node.attrs.ncmId as string) : ''
    const title = String(node.attrs.title || '').trim() || (id ? defaultTitle(type, id) : '网易云音乐')
    return [
      'div',
      { class: 'ncm-card', 'data-ncm-card': '', 'data-type': type, 'data-id': id },
      ['a', { href: id ? canonicalUrl(type, id) : 'https://music.163.com/' }, title],
    ]
  },

  renderText({ node }) {
    const type = normalizeType(node.attrs.ncmType)
    const id = String(node.attrs.ncmId || '')
    return isValidId(id) ? `${node.attrs.title || defaultTitle(type, id)} ${canonicalUrl(type, id)}` : ''
  },

  addNodeView() {
    return VueNodeViewRenderer(NcmCardView)
  },

  addOptions() {
    return {
      ...this.parent?.(),
      getCommandMenuItems() {
        return {
          priority: 200,
          icon: markRaw(RiNeteaseCloudMusicLine),
          title: TITLE,
          keywords: ['ncm', 'music', 'netease', 'wangyiyun', 'yinyue', '网易云', '音乐', '歌曲', '歌单', '专辑'],
          command: ({ editor, range }: { editor: Editor; range: Range }) => {
            insertCard(editor, range)
          },
        }
      },
      getToolboxItems({ editor }: { editor: Editor }) {
        return {
          priority: 200,
          component: markRaw(ToolboxItem),
          props: {
            editor,
            icon: markRaw(RiNeteaseCloudMusicLine),
            title: TITLE,
            description: '插入网易云的歌曲、歌单或专辑，访客可以直接播放',
            action: () => insertCard(editor),
          },
        }
      },
      getBubbleMenu({ editor }: { editor: Editor }): NodeBubbleMenuType {
        return {
          pluginKey: NCM_CARD_BUBBLE_MENU_KEY,
          shouldShow: ({ state }: { state: EditorState }) => isActive(state, NCM_CARD_NODE),
          items: [
            {
              priority: 10,
              props: {
                icon: markRaw(RiEditLine),
                title: '修改',
                action: () => {
                  openEditor(editor)
                },
              },
            },
            {
              priority: 20,
              props: {
                icon: markRaw(RiExternalLinkLine),
                title: '在网易云音乐打开',
                action: () => {
                  const attrs = editor.getAttributes(NCM_CARD_NODE)
                  const id = String(attrs.ncmId || '')
                  if (isValidId(id)) {
                    window.open(canonicalUrl(normalizeType(attrs.ncmType), id), '_blank', 'noopener')
                  }
                },
              },
            },
            {
              priority: 30,
              component: markRaw(BlockActionSeparator),
            },
            {
              priority: 40,
              props: {
                icon: markRaw(RiDeleteBinLine),
                title: '删除',
                action: ({ editor: e }: { editor: Editor }) => {
                  deleteNode(NCM_CARD_NODE, e)
                },
              },
            },
          ],
        }
      },
    }
  },
})

export default ExtensionNcmCard
