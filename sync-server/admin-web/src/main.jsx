import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import {
  Alert, App as AntApp, Avatar, Badge, Breadcrumb, Button, Card, Col, ConfigProvider, Descriptions, Drawer, Empty, Grid, Input, Layout, List,
  Menu, Modal, Popconfirm, Progress, Radio, Result, Row, Segmented, Select, Space, Spin, Statistic, Switch, Table, Tabs, Tag, Timeline, Upload
} from 'antd'
import {
  AudioOutlined, BellOutlined, CloudSyncOutlined, DashboardOutlined, FileSearchOutlined, HddOutlined, MenuOutlined, MobileOutlined,
  ReadOutlined, TeamOutlined, UploadOutlined, UserOutlined
} from '@ant-design/icons'
import zhCN from 'antd/locale/zh_CN'
import { StyleProvider, legacyLogicalPropertiesTransformer } from '@ant-design/cssinjs'
import { api, createPairing, claimPairing, deviceToken, setDeviceToken, signOut, onSignedOut } from './api'

const { Sider, Content, Header } = Layout

const COURSE_KINDS = [['approved', '已公开'], ['pending', '待审核'], ['returned', '已打回']]
const ACCESS = { none: ['未申请', 'default'], pending: ['待批准', 'gold'], approved: ['可看原文', 'green'], revoked: ['已收回', 'default'] }
const AUDIT_ACTIONS = {
  'course.request': '申请公开课程', 'course.approved': '批准课程', 'course.returned': '打回课程',
  'course.rejected': '驳回课程（旧）', 'course.taken_down': '下架课程（旧）', 'course.deleted': '删除课程',
  'lesson.publish': '上传课堂', 'lesson.withdraw_request': '申请撤回课堂', 'lesson.withdraw_dismissed': '不同意撤回',
  'lesson.taken_down': '下架课堂', 'lesson.active': '恢复课堂',
  'material.uploaded': '上传资料', 'material.moved': '移动资料', 'material.deleted': '删除资料',
  'uploader.create': '开通上传', 'uploader.linked': '关联上传者', 'uploader.rotate': '更换 Token', 'uploader.disabled': '停用上传', 'uploader.active': '启用上传',
  'reader.request_transcripts': '申请查看原文', 'reader.approve': '允许查看原文', 'reader.revoke': '收回原文权限',
  'reader.role': '修改角色', 'reader.make_admin': '设为管理员', 'reader.remove_admin': '取消管理员', 'reader.remark': '修改备注',
  'device.approve': '绑定设备', 'device.remove': '移除设备', 'config.update': '更新云端配置'
}

const pad = (n) => String(n).padStart(2, '0')
function when(ms) {
  if (!ms) return '—'
  const d = new Date(ms), now = new Date()
  const text = `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return d.getFullYear() === now.getFullYear() ? text : `${d.getFullYear()}-${text}`
}
function bytes(n) {
  if (n == null) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(0)} KB`
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`
  return `${(n / 1024 ** 3).toFixed(2)} GB`
}
const courseLabel = (c) => `${c.courseName} · ${c.teacherName}`
const muted = { color: '#566360' }

// Loads on mount and whenever the route's key changes; pages call reload after each change.
function useLoad(loader, deps) {
  const { message } = AntApp.useApp()
  const [state, setState] = useState({ loading: true, data: null, error: null })
  const reload = useCallback(() => {
    setState((s) => ({ ...s, loading: true }))
    return loader()
      .then((data) => setState({ loading: false, data, error: null }))
      .catch((error) => { setState({ loading: false, data: null, error: error.message }); if (error.status !== 401) message.error(error.message) })
  }, deps) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { reload() }, [reload])
  return { ...state, reload }
}

// The one way a state change happens: what it applies to, what it does, and a reason where it needs one.
function ActionDialog({ action, close }) {
  const { message } = AntApp.useApp()
  const [text, setText] = useState(action.initial || '')
  const [busy, setBusy] = useState(false)
  const blocked = (action.field && action.required && !text.trim()) || (action.typed && text !== '删除')
  const ok = () => {
    setBusy(true)
    action.run(text.trim())
      .then(() => { message.success(action.done || `${action.title}完成`); close(true) })
      .catch((error) => { message.error(error.message); setBusy(false) })
  }
  return (
    <Modal open title={action.title} okText={action.ok} cancelText="取消" onCancel={() => close(false)} onOk={ok} confirmLoading={busy}
      okButtonProps={{ danger: action.danger, disabled: blocked }}>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {action.target && <div><span style={muted}>对象</span> {action.target}</div>}
        {action.effect && <div style={muted}>{action.effect}</div>}
        {action.field && <div><label htmlFor="action-field">{action.field}{action.required ? '（必填）' : ''}</label>
          <Input.TextArea id="action-field" rows={action.rows || 3} maxLength={action.max || 1000} value={text} onChange={(e) => setText(e.target.value)} /></div>}
        {action.typed && <div><label htmlFor="action-typed">输入“删除”确认</label><Input id="action-typed" value={text} onChange={(e) => setText(e.target.value)} /></div>}
      </Space>
    </Modal>
  )
}

const courseAction = {
  approve: { title: '批准公开', ok: '批准', done: '已批准', effect: '已上传和以后上传的课堂都会直接公开。', call: (id) => api('POST', `/courses/${id}/approve`, {}) },
  republish: { title: '重新公开', ok: '重新公开', done: '已公开', effect: '读者会重新看到这门课。单独下架的课堂仍保持下架。', call: (id) => api('POST', `/courses/${id}/approve`, {}) },
  send: { title: '打回', ok: '打回', done: '已打回', danger: true, field: '打回原因', required: true, effect: '读者看不到这门课的课堂，原因会显示给上传者。修改后可以重新申请，也可以由你重新公开。', call: (id, note) => api('POST', `/courses/${id}/return`, { note }) },
  remove: { title: '删除', ok: '确认删除', done: '已删除', danger: true, typed: true, effect: '课堂、版本和资料全部删除，不能撤销。上传者再次发布时要重新审核。', call: (id) => api('DELETE', `/courses/${id}`) }
}
const ROW_OPS = {
  approved: [['打回', 'send', true]],
  pending: [['批准', 'approve', false, true], ['打回', 'send', true]],
  returned: [['重新公开', 'republish', false, true], ['删除', 'remove', true]]
}
const BATCH_OPS = {
  approved: [['批量打回', 'send', true]],
  pending: [['批量批准', 'approve', false, true], ['批量打回', 'send', true]],
  returned: [['批量重新公开', 'republish', false, true]]
}
function askCourse(setAction, key, courses, after) {
  const a = courseAction[key]
  setAction({
    ...a, target: courses.length === 1 ? courseLabel(courses[0]) : `${courses.length} 门课程`,
    // One at a time, so a failure part way names what is left instead of hiding it.
    run: async (note) => { for (const c of courses) await a.call(c.courseId, note) },
    after
  })
}

// ---- pages -------------------------------------------------------------------------------

function Overview({ go, summary }) {
  const storage = useLoad(() => api('GET', '/storage'), [])
  const queue = useLoad(loadQueue, [])
  const c = summary?.courses || {}
  const pending = (summary?.pendingCourses || 0) + (summary?.pendingReaders || 0) + (summary?.withdrawRequests || 0)
  const total = (c.approved || 0) + (c.pending || 0) + (c.returned || 0)
  const tile = (title, value, suffix, route, Icon) => (
    <Col xs={12} md={8} xl={4} key={title}><Card size="small" hoverable onClick={() => go(route)}><Statistic title={title} value={value} suffix={suffix} prefix={<Icon />} /></Card></Col>
  )
  const disk = storage.data?.disk
  return (
    <Space direction="vertical" size={14} style={{ width: '100%' }}>
      <Row gutter={[10, 10]}>
        {tile('待处理', pending, '项', 'queue', BellOutlined)}
        {tile('课程', total, '门', 'courses/approved', ReadOutlined)}
        {tile('课堂', summary?.lessons ?? 0, '节', 'courses/approved', AudioOutlined)}
        {tile('用户', summary?.readers ?? 0, '人', 'users', TeamOutlined)}
        {tile('设备', summary?.devices ?? 0, '台', 'devices', MobileOutlined)}
        <Col xs={12} md={8} xl={4}><Card size="small" hoverable onClick={() => go('storage')}><Statistic title="占用总空间" value={storage.data ? bytes(storage.data.totalBytes) : '—'} prefix={<HddOutlined />} /></Card></Col>
      </Row>
      <Row gutter={[12, 12]}>
        <Col xs={24} lg={14}>
          <Card size="small" title="待处理" extra={<a onClick={() => go('queue')}>全部</a>}>
            {queue.data?.length ? <List size="small" dataSource={queue.data.slice(0, 5)} renderItem={(q) => (
              <List.Item extra={<span style={muted}>{when(q.at)}</span>}><Space><Tag color={q.color}>{q.type}</Tag>{q.title}</Space></List.Item>
            )} /> : <Empty description="没有待处理的事" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card size="small" title="服务器磁盘" extra={<a onClick={() => go('storage')}>明细</a>}>
            {disk ? <><Progress percent={Math.round(disk.usedBytes / disk.totalBytes * 100)} />
              <div style={muted}>已用 {bytes(disk.usedBytes)}，共 {bytes(disk.totalBytes)}。Listen 占 {bytes(storage.data.totalBytes)}。</div></> : <Spin />}
          </Card>
        </Col>
      </Row>
    </Space>
  )
}

async function loadQueue() {
  const [{ courses }, { lessons }, { readers }] = await Promise.all([
    api('GET', '/courses?status=pending'), api('GET', '/lessons?withdrawRequested=true'), api('GET', '/readers')
  ])
  return [
    ...courses.map((c) => ({ key: `c${c.courseId}`, type: '课程', color: 'gold', title: courseLabel(c), detail: `${c.lessonCount} 节 · ${c.requestedBy || ''}${c.reRequested ? ` · 重新申请，上次：${c.reviewNote}` : ''}`, at: c.requestedAt, course: c })),
    ...lessons.filter((l) => l.status === 'active').map((l) => ({ key: `l${l.lessonId}`, type: '撤回', color: 'red', title: `${l.title} · ${l.course.courseName}`, detail: l.withdrawReason, at: l.withdrawRequestedAt, lesson: l })),
    ...readers.filter((r) => r.transcriptAccess === 'pending').map((r) => ({ key: `r${r.readerId}`, type: '原文', color: 'blue', title: r.displayName || '未命名', detail: r.requestNote || '', at: r.requestedAt, reader: r }))
  ].sort((a, b) => (a.at || 0) - (b.at || 0))
}

function Queue({ refresh }) {
  const { loading, data, reload } = useLoad(loadQueue, [])
  const [tab, setTab] = useState('全部')
  const [action, setAction] = useState(null)
  const [preview, setPreview] = useState(null)
  const rows = (data || []).filter((q) => tab === '全部' || q.type === tab)
  const count = (t) => (t === '全部' ? data?.length : data?.filter((q) => q.type === t).length) || 0
  const actions = (q) => {
    if (q.course) return [
      ['批准', () => askCourse(setAction, 'approve', [q.course]), 'primary'],
      ['打回', () => askCourse(setAction, 'send', [q.course]), 'danger']
    ]
    if (q.lesson) return [
      ['同意', () => setAction({ title: '同意撤回', ok: '同意并下架', danger: true, target: q.title, effect: '这一节会下架，申请随之结束。', done: '已下架',
        run: () => api('POST', `/lessons/${q.lesson.lessonId}/takedown`, { note: '同意上传者的撤回申请' }) }), 'primary'],
      ['拒绝', () => setAction({ title: '不同意撤回', ok: '不同意', target: q.title, field: '原因', required: true, effect: '这一节继续公开，上传者可以再次申请。', done: '已处理',
        run: (note) => api('POST', `/lessons/${q.lesson.lessonId}/withdraw-dismiss`, { note }) }), 'danger']
    ]
    return [
      ['允许', () => setAction({ title: '允许查看原文', ok: '允许', target: q.title, effect: '对所有课程生效。', done: '已允许', run: () => api('POST', `/readers/${q.reader.readerId}/approve`, {}) }), 'primary'],
      ['拒绝', () => setAction({ title: '拒绝查看原文', ok: '拒绝', danger: true, target: q.title, effect: '对方可以再次申请。', done: '已拒绝', run: () => api('POST', `/readers/${q.reader.readerId}/revoke`, {}) }), 'danger']
    ]
  }
  return (
    <Card size="small">
      <Tabs activeKey={tab} onChange={setTab} items={['全部', '课程', '撤回', '原文'].map((t) => ({ key: t, label: `${t} ${count(t)}` }))} />
      <Table size="small" rowKey="key" loading={loading} dataSource={rows} pagination={false} scroll={{ x: 760 }} locale={{ emptyText: '没有待处理的事' }} columns={[
        { title: '类型', dataIndex: 'type', width: 70, render: (t, q) => <Tag color={q.color}>{t}</Tag> },
        { title: '内容', dataIndex: 'title', render: (t, q) => q.lesson ? <a onClick={() => setPreview(q.lesson.lessonId)}>{t}</a> : q.course ? <a href={`#/course/${q.course.courseId}`}>{t}</a> : t },
        { title: '说明', dataIndex: 'detail', render: (d) => <span style={muted}>{d}</span> },
        { title: '申请时间', dataIndex: 'at', width: 110, render: when },
        { title: '操作', width: 140, render: (_, q) => <Space size={6}>{actions(q).map(([label, run, kind]) => <Button key={label} size="small" type={kind === 'primary' ? 'primary' : 'default'} danger={kind === 'danger'} onClick={run}>{label}</Button>)}</Space> }
      ]} />
      {action && <ActionDialog action={action} close={(changed) => { setAction(null); if (changed) { reload(); refresh() } }} />}
      <LessonDrawer lessonId={preview} close={() => setPreview(null)} />
    </Card>
  )
}

function Courses({ kind, refresh }) {
  const wide = Grid.useBreakpoint().lg
  const { loading, data, reload } = useLoad(() => api('GET', `/courses?status=${kind}`), [kind])
  const [q, setQ] = useState('')
  const [term, setTerm] = useState('all')
  const [picked, setPicked] = useState([])
  const [action, setAction] = useState(null)
  useEffect(() => { setPicked([]); setQ(''); setTerm('all') }, [kind])
  const courses = data?.courses || []
  const terms = useMemo(() => [...new Set(courses.map((c) => c.term))].sort().reverse(), [courses])
  const rows = courses.filter((c) => (term === 'all' || c.term === term) && (!q || courseLabel(c).includes(q)))
  const chosen = courses.filter((c) => picked.includes(c.courseId))
  return (
    <Space direction="vertical" size={10} style={{ width: '100%' }}>
      <Space wrap>
        <Input.Search id="course-search" allowClear placeholder="搜索课程或教师" style={{ width: 220, maxWidth: '100%' }} value={q} onChange={(e) => setQ(e.target.value)} />
        <Select id="course-term" style={{ width: 170 }} value={term} onChange={setTerm} options={[{ value: 'all', label: '全部学期' }, ...terms.map((t) => ({ value: t, label: t }))]} />
      </Space>
      {picked.length > 0 && <Alert type="info" message={<Space wrap>已选 {picked.length} 门
        {BATCH_OPS[kind].map(([label, key, danger, primary]) => <Button key={key} size="small" danger={danger} type={primary ? 'primary' : 'default'} onClick={() => askCourse(setAction, key, chosen)}>{label}</Button>)}
        <a onClick={() => setPicked([])}>取消选择</a></Space>} />}
      <Card size="small" styles={{ body: { padding: 0 } }}>
        <Table size="small" rowKey="courseId" loading={loading} dataSource={rows} scroll={{ x: 960 }} locale={{ emptyText: '这一类没有课程' }}
          rowSelection={{ selectedRowKeys: picked, onChange: setPicked }} pagination={{ pageSize: 20, showSizeChanger: false, showTotal: (t) => `共 ${t} 门` }} columns={[
            { title: '课程', dataIndex: 'courseName', sorter: (a, b) => a.courseName.localeCompare(b.courseName, 'zh'), render: (name, c) => <a href={`#/course/${c.courseId}`}>{name}</a> },
            { title: '教师', dataIndex: 'teacherName', width: 90 },
            { title: '学期', dataIndex: 'term', width: 150 },
            { title: '课堂', dataIndex: 'lessonCount', width: 70, align: 'right', sorter: (a, b) => a.lessonCount - b.lessonCount },
            { title: '资料', dataIndex: 'materialCount', width: 70, align: 'right' },
            { title: '资料大小', dataIndex: 'materialBytes', width: 90, align: 'right', sorter: (a, b) => a.materialBytes - b.materialBytes, render: bytes },
            { title: '上传者', dataIndex: 'requestedBy', width: 100 },
            { title: '最近上传', dataIndex: 'lastUploadAt', width: 110, sorter: (a, b) => (a.lastUploadAt || 0) - (b.lastUploadAt || 0), defaultSortOrder: 'descend', render: when },
            ...(kind === 'returned' ? [{ title: '打回原因', dataIndex: 'reviewNote', render: (v) => <span style={muted}>{v || '—'}</span> }] : []),
            { title: '操作', width: 150, fixed: wide ? 'right' : undefined, render: (_, c) => <Space size={6}>{ROW_OPS[kind].map(([label, key, danger, primary]) => <Button key={key} size="small" danger={danger} type={primary ? 'primary' : 'default'} onClick={() => askCourse(setAction, key, [c])}>{label}</Button>)}</Space> }
          ]} />
      </Card>
      {action && <ActionDialog action={action} close={(changed) => { setAction(null); if (changed) { setPicked([]); reload(); refresh() } }} />}
    </Space>
  )
}

function CourseDetail({ id, refresh }) {
  const { message } = AntApp.useApp()
  const { loading, data, error, reload } = useLoad(() => api('GET', `/courses/${id}`), [id])
  const materials = useLoad(() => api('GET', `/courses/${id}/materials`), [id])
  const log = useLoad(() => api('GET', `/audit?limit=100`), [id])
  const [action, setAction] = useState(null)
  const [preview, setPreview] = useState(null)
  const [uploading, setUploading] = useState(false)
  const [category, setCategory] = useState('note')
  const [scope, setScope] = useState('course')
  if (error) return <Result status="info" title="这门课不存在或已删除" extra={<Button href="#/courses/approved">回到课程列表</Button>} />
  if (!data) return <Spin spinning={loading} />
  const { course: c, lessons } = data
  const kindName = Object.fromEntries(COURSE_KINDS)[c.status]
  const closeAction = (changed) => { const a = action; setAction(null); if (changed) { refresh(); if (a.leave) location.hash = `#/courses/${a.leave}`; else { reload(); log.reload() } } }
  const upload = async ({ file, onSuccess, onError }) => {
    setUploading(true)
    try {
      const query = new URLSearchParams({ fileName: file.name, category })
      if (scope !== 'course') query.set('lessonId', scope)
      await api('POST', `/courses/${id}/materials?${query}`, file, 'application/octet-stream')
      message.success('已上传'); materials.reload(); onSuccess()
    } catch (e) { message.error(e.message); onError(e) } finally { setUploading(false) }
  }
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Breadcrumb items={[{ title: <a href={`#/courses/${c.status}`}>{kindName}</a> }, { title: c.courseName }]} />
      <Card size="small" title={<Space>{courseLabel(c)}<Tag color={{ approved: 'green', pending: 'gold', returned: 'default' }[c.status]}>{kindName}</Tag></Space>}
        extra={<Space size={6}>{ROW_OPS[c.status].map(([label, key, danger, primary]) => (
          <Button key={key} size="small" danger={danger} type={primary ? 'primary' : 'default'} onClick={() => askCourse((a) => setAction({ ...a, leave: key === 'remove' ? 'returned' : null }), key, [c])}>{label}</Button>
        ))}</Space>}>
        <Descriptions size="small" column={{ xs: 1, md: 3 }} items={[
          { key: 1, label: '学校', children: c.schoolName }, { key: 2, label: '学期', children: c.term }, { key: 3, label: '上传者', children: c.requestedBy || '—' },
          { key: 4, label: '申请时间', children: when(c.requestedAt) }, { key: 5, label: '最近上传', children: when(c.lastUploadAt) },
          ...(c.reviewNote ? [{ key: 6, label: '原因', children: c.reviewNote }] : [])
        ]} />
      </Card>
      <Card size="small"><Tabs items={[
        { key: 'lessons', label: `课堂 ${lessons.length}`, children: (
          <Table size="small" rowKey="lessonId" dataSource={lessons} pagination={false} scroll={{ x: 720 }} columns={[
            { title: '标题', dataIndex: 'title', render: (t, l) => <a onClick={() => setPreview(l.lessonId)}>{t}</a> },
            { title: '上课时间', dataIndex: 'startedAt', width: 110, render: when },
            { title: '段落', dataIndex: 'segmentCount', width: 70, align: 'right' },
            { title: '笔记', dataIndex: 'noteCount', width: 60, align: 'right' },
            { title: '状态', width: 100, render: (_, l) => l.withdrawRequestedAt ? <Tag color="gold">申请撤回</Tag> : l.status === 'active' ? <Tag color="green">公开</Tag> : <Tag>已下架</Tag> },
            { title: '操作', width: 90, render: (_, l) => l.status === 'active'
              ? <Button size="small" danger onClick={() => setAction({ title: '下架课堂', ok: '下架', danger: true, target: l.title, field: '下架原因', required: true, effect: '读者看不到这一节，之后可以恢复。', done: '已下架', run: (note) => api('POST', `/lessons/${l.lessonId}/takedown`, { note }) })}>下架</Button>
              : <Button size="small" onClick={() => setAction({ title: '恢复课堂', ok: '恢复', target: l.title, effect: '课程公开时，读者会重新看到这一节。', done: '已恢复', run: () => api('POST', `/lessons/${l.lessonId}/restore`, {}) })}>恢复</Button> }
          ]} />
        ) },
        { key: 'materials', label: `笔记与资料 ${materials.data?.materials?.length ?? 0}`, children: (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Space wrap>
              <Select id="material-scope" size="small" style={{ width: 200 }} value={scope} onChange={setScope} options={[{ value: 'course', label: '整门课' }, ...lessons.map((l) => ({ value: l.lessonId, label: l.title }))]} />
              <Select id="material-category" size="small" style={{ width: 80 }} value={category} onChange={setCategory} options={[{ value: 'note', label: '笔记' }, { value: 'material', label: '资料' }]} />
              <Upload customRequest={upload} showUploadList={false} multiple><Button size="small" type="primary" loading={uploading} icon={<UploadOutlined />}>选择文件上传</Button></Upload>
            </Space>
            <Table size="small" rowKey="materialId" pagination={false} dataSource={materials.data?.materials || []} scroll={{ x: 640 }} locale={{ emptyText: '还没有笔记或资料' }} columns={[
              { title: '类型', dataIndex: 'category', width: 70, render: (v) => <Tag color={v === 'note' ? 'green' : 'blue'}>{v === 'note' ? '笔记' : '资料'}</Tag> },
              { title: '文件', dataIndex: 'fileName' }, { title: '范围', dataIndex: 'lessonTitle', render: (v) => v || '整门课' }, { title: '大小', dataIndex: 'size', width: 100, align: 'right', render: bytes }, { title: '上传时间', dataIndex: 'createdAt', width: 110, render: when },
              { title: '操作', width: 70, render: (_, m) => <Popconfirm title="删除这份资料？" okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => api('DELETE', `/materials/${m.materialId}`).then(() => materials.reload()).catch((e) => message.error(e.message))}><Button size="small" danger>删除</Button></Popconfirm> }
            ]} />
          </Space>
        ) },
        { key: 'log', label: '操作记录', children: (
          <Timeline items={(log.data?.entries || []).filter((e) => e.target === `course:${id}` || lessons.some((l) => e.target === `lesson:${l.lessonId}`)).slice(0, 30).map((e) => ({ children: `${when(e.at)} ${e.actorName} ${AUDIT_ACTIONS[e.action] || e.action}${e.detail ? `：${e.detail}` : ''}` }))} />
        ) }
      ]} /></Card>
      {action && <ActionDialog action={action} close={closeAction} />}
      <LessonDrawer lessonId={preview} close={() => setPreview(null)} />
    </Space>
  )
}

function LessonDrawer({ lessonId, close }) {
  const [lesson, setLesson] = useState(null)
  useEffect(() => { setLesson(null); if (lessonId) api('GET', `/lessons/${lessonId}`).then(setLesson).catch(() => close()) }, [lessonId]) // eslint-disable-line react-hooks/exhaustive-deps
  const clock = (ms) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}` }
  return (
    <Drawer open={!!lessonId} width="min(640px, 100vw)" onClose={close} title={lesson ? lesson.title : '课堂'}>
      {!lesson ? <Spin /> : <Tabs items={[
        { key: 'text', label: `原文 ${lesson.segments.length}`, children: lesson.segments.map((s) => <p key={s.seq} style={{ margin: '0 0 8px' }}><span style={muted}>{clock(s.startTime)} </span>{s.displayText}</p>) },
        { key: 'notes', label: `笔记 ${lesson.notes.length}`, children: lesson.notes.length ? lesson.notes.map((n) => <div key={n.noteId} style={{ whiteSpace: 'pre-wrap', marginBottom: 16 }}><strong>{n.title}</strong>{'\n'}{n.content}</div>) : <Empty description="没有笔记" /> }
      ]} />}
    </Drawer>
  )
}

const ROLES = [
  ['reader', '读者', 'default', '看公开的笔记和资料'],
  ['transcript', '原文读者', 'blue', '读者的权限，再加上看课堂原文'],
  ['uploader', '上传者', 'green', '原文读者的权限，再加上绑定手机、上传课堂和资料'],
  ['admin', '管理员', 'cyan', '全部权限，包括审核课程、管理用户和进入后台']
]

function RoleDialog({ user, mine, close }) {
  const { message } = AntApp.useApp()
  const [name, setName] = useState(user.displayName || '')
  const [role, setRole] = useState(user.role)
  const [busy, setBusy] = useState(false)
  const publishes = role === 'uploader' || role === 'admin'
  const save = () => {
    setBusy(true)
    api('POST', `/readers/${user.readerId}/role`, { role, name: name.trim() || null })
      .then(() => { message.success('已保存'); close(true) })
      .catch((e) => { message.error(e.message); setBusy(false) })
  }
  return (
    <Modal open title="修改用户" okText="保存" cancelText="取消" confirmLoading={busy} onCancel={() => close(false)} onOk={save} okButtonProps={{ disabled: publishes && !name.trim() }}>
      <Space direction="vertical" size={14} style={{ width: '100%' }}>
        <div><label htmlFor="user-name">名称</label>
          <Input id="user-name" maxLength={20} value={name} onChange={(e) => setName(e.target.value)} />
          <div style={{ ...muted, fontSize: 12, marginTop: 4 }}>{publishes ? '会公开显示在 Ta 上传的课堂上。' : '只有管理员看得到。'}</div></div>
        {user.requestNote && <div><span style={muted}>申请留言</span> {user.requestNote}</div>}
        <div><div style={{ marginBottom: 6 }}>角色</div>
          <Radio.Group value={role} onChange={(e) => setRole(e.target.value)} style={{ width: '100%' }} disabled={mine}>
            <Space direction="vertical" style={{ width: '100%' }}>
              {ROLES.map(([id, label, , can]) => <Radio key={id} value={id}><strong style={{ fontWeight: 500 }}>{label}</strong><div style={muted}>{can}</div></Radio>)}
            </Space>
          </Radio.Group>
          {mine && <div style={{ ...muted, fontSize: 12, marginTop: 6 }}>不能修改自己的角色。</div>}</div>
      </Space>
    </Modal>
  )
}

function Users({ refresh }) {
  const { loading, data, reload } = useLoad(() => api('GET', '/readers'), [])
  const me = useLoad(() => api('GET', '/whoami'), [])
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState('all')
  const [editing, setEditing] = useState(null)
  const all = data?.readers || []
  const rows = all.filter((r) => (filter === 'all' || r.role === filter) && (!q || `${r.displayName || ''}${r.requestNote || ''}`.includes(q)))
  const count = (id) => all.filter((r) => r.role === id).length
  return (
    <Space direction="vertical" size={10} style={{ width: '100%' }}>
      <Space wrap>
        <Segmented value={filter} onChange={setFilter} options={[{ value: 'all', label: `全部 ${all.length}` }, ...ROLES.map(([id, label]) => ({ value: id, label: `${label} ${count(id)}` }))]} />
        <Input.Search id="user-search" allowClear placeholder="搜索名称或留言" style={{ width: 220, maxWidth: '100%' }} onChange={(e) => setQ(e.target.value)} />
      </Space>
      <Card size="small" styles={{ body: { padding: 0 } }}>
        <Table size="small" rowKey="readerId" loading={loading} dataSource={rows} scroll={{ x: 760 }} pagination={{ pageSize: 30, hideOnSinglePage: true }} columns={[
          { title: '名称', render: (_, r) => <Space>{r.displayName || '未命名'}{r.readerId === me.data?.readerId && <Tag color="blue">你</Tag>}</Space> },
          { title: '角色', width: 170, render: (_, r) => { const x = ROLES.find((o) => o[0] === r.role); return <Space size={4}><Tag color={x[2]}>{x[1]}</Tag>{r.transcriptAccess === 'pending' && <Tag color="gold">申请原文</Tag>}</Space> } },
          { title: '微信账号', dataIndex: 'openidHint', width: 110, render: (v) => <span style={muted}>{v}</span> },
          { title: '申请留言', dataIndex: 'requestNote', render: (v) => <span style={muted}>{v || '—'}</span> },
          { title: '最近登录', dataIndex: 'lastSeenAt', width: 110, render: when },
          { title: '操作', width: 70, render: (_, r) => <Button size="small" onClick={() => setEditing(r)}>修改</Button> }
        ]} />
      </Card>
      {editing && <RoleDialog user={editing} mine={editing.readerId === me.data?.readerId} close={(changed) => { setEditing(null); if (changed) { reload(); refresh() } }} />}
    </Space>
  )
}

function Devices({ refresh }) {
  const { loading, data, reload } = useLoad(() => api('GET', '/devices'), [])
  const [action, setAction] = useState(null)
  return (
    <Card size="small" styles={{ body: { padding: 0 } }}>
      <Table size="small" rowKey="deviceId" loading={loading} dataSource={data?.devices || []} pagination={false} scroll={{ x: 640 }} locale={{ emptyText: '没有绑定的设备' }} columns={[
        { title: '设备', dataIndex: 'name' }, { title: '账号', dataIndex: 'owner', width: 110 },
        { title: '绑定时间', dataIndex: 'createdAt', width: 110, render: when }, { title: '最近在线', dataIndex: 'lastSeenAt', width: 110, render: when },
        { title: '操作', width: 80, render: (_, d) => <Button size="small" danger onClick={() => setAction({ title: '移除设备', ok: '移除', danger: true, target: d.name, effect: '它联网后会清除本机的配置。手机丢失时，还要更换相关密钥。', done: '已移除', run: () => api('DELETE', `/devices/${d.deviceId}`) })}>移除</Button> }
      ]} />
      {action && <ActionDialog action={action} close={(changed) => { setAction(null); if (changed) { reload(); refresh() } }} />}
    </Card>
  )
}

function Storage() {
  const { loading, data } = useLoad(() => api('GET', '/storage'), [])
  if (!data) return <Spin spinning={loading} />
  const { parts, totalBytes, disk, sync } = data
  const tile = (title, value, suffix) => <Col xs={12} md={6} key={title}><Card size="small"><Statistic title={title} value={value} suffix={suffix} /></Card></Col>
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Row gutter={[10, 10]}>
        {tile('占用总空间', bytes(totalBytes))}
        {tile('同步的课堂', sync ? sync.sessions : '—', sync ? '节' : '')}
        {tile('同步的段落', sync ? sync.segments : '—', sync ? '段' : '')}
        {tile('已删除记录', sync ? sync.deletedRecords : '—', sync ? '条' : '')}
      </Row>
      <Row gutter={[12, 12]}>
        <Col xs={24} lg={14}>
          <Card size="small" title="空间明细"><Table size="small" rowKey="key" pagination={false} dataSource={parts} columns={[
            { title: '内容', dataIndex: 'label' }, { title: '大小', dataIndex: 'bytes', width: 100, align: 'right', render: bytes },
            { title: '占比', width: 170, render: (_, p) => <Progress size="small" percent={totalBytes ? Math.round(p.bytes / totalBytes * 100) : 0} /> }
          ]} /></Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card size="small" title="同步服务"><Descriptions size="small" column={1} items={[
            { key: 1, label: '状态', children: sync ? <Badge status="success" text="运行正常" /> : <Badge status="error" text="连不上同步服务，或它的版本还没有统计接口" /> },
            { key: 2, label: '最近一次变更', children: when(sync?.lastChangedAt) },
            { key: 3, label: '服务器磁盘', children: `已用 ${bytes(disk.usedBytes)}，共 ${bytes(disk.totalBytes)}` }
          ]} /></Card>
        </Col>
      </Row>
      <Card size="small" title="按课程查看同步数据"><Table size="small" rowKey="courseName" dataSource={sync?.courses || []} scroll={{ x: 520 }} pagination={{ pageSize: 15, hideOnSinglePage: true }} locale={{ emptyText: '没有同步数据' }} columns={[
        { title: '课程', dataIndex: 'courseName' },
        { title: '课堂', dataIndex: 'sessions', width: 80, align: 'right', sorter: (a, b) => a.sessions - b.sessions },
        { title: '段落', dataIndex: 'segments', width: 90, align: 'right', sorter: (a, b) => a.segments - b.segments },
        { title: '最近变更', dataIndex: 'lastChangedAt', width: 110, render: when }
      ]} /></Card>
    </Space>
  )
}

function Audit() {
  const [action, setActionFilter] = useState('')
  const [q, setQ] = useState('')
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [end, setEnd] = useState(false)
  const { message } = AntApp.useApp()
  const load = useCallback((before) => {
    setLoading(true)
    const query = new URLSearchParams({ limit: '50' })
    if (before) query.set('before', before)
    if (action) query.set('action', action)
    if (q) query.set('q', q)
    return api('GET', `/audit?${query}`)
      .then(({ entries }) => { setRows((old) => before ? [...old, ...entries] : entries); setEnd(entries.length < 50) })
      .catch((e) => message.error(e.message))
      .finally(() => setLoading(false))
  }, [action, q]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load() }, [load])
  return (
    <Space direction="vertical" size={10} style={{ width: '100%' }}>
      <Space wrap>
        <Select id="audit-action" style={{ width: 170 }} value={action} onChange={setActionFilter} options={[{ value: '', label: '全部操作' }, ...Object.entries(AUDIT_ACTIONS).map(([value, label]) => ({ value, label }))]} />
        <Input.Search id="audit-search" allowClear placeholder="搜索原因或说明" style={{ width: 240, maxWidth: '100%' }} onSearch={setQ} />
      </Space>
      <Card size="small" styles={{ body: { padding: 0 } }}>
        <Table size="small" rowKey="id" loading={loading} dataSource={rows} pagination={false} scroll={{ x: 700 }} columns={[
          { title: '时间', dataIndex: 'at', width: 110, render: when }, { title: '操作者', dataIndex: 'actorName', width: 110 },
          { title: '操作', dataIndex: 'action', width: 140, render: (v) => <Tag>{AUDIT_ACTIONS[v] || v}</Tag> },
          { title: '对象', dataIndex: 'targetName', render: (v) => v || <span style={muted}>—</span> },
          { title: '说明', dataIndex: 'detail', render: (v) => <span style={muted}>{v || '—'}</span> }
        ]} />
      </Card>
      {!end && rows.length > 0 && <Button onClick={() => load(rows[rows.length - 1].id)} loading={loading}>加载更早的记录</Button>}
    </Space>
  )
}

// A phone shows a code and the mini program approves it; the console signs in the same way.
function Login({ onDone }) {
  const [ticket, setTicket] = useState(null)
  const [error, setError] = useState('')
  const start = useCallback(() => {
    setError(''); setTicket(null)
    const browser = /Edg/.test(navigator.userAgent) ? 'Edge' : /Chrome/.test(navigator.userAgent) ? 'Chrome' : /Firefox/.test(navigator.userAgent) ? 'Firefox' : /Safari/.test(navigator.userAgent) ? 'Safari' : '浏览器'
    createPairing(`网页后台 · ${browser}`).then(setTicket).catch((e) => setError(e.message))
  }, [])
  useEffect(() => { start() }, [start])
  useEffect(() => {
    if (!ticket) return undefined
    const timer = setInterval(() => {
      claimPairing(ticket.claim).then((result) => {
        if (result.status === 'approved') { clearInterval(timer); setDeviceToken(result.deviceToken); onDone() }
      }).catch((e) => { if (e.status === 410) { clearInterval(timer); setTicket(null); setError(e.message) } })
    }, 3000)
    return () => clearInterval(timer)
  }, [ticket, onDone])
  return (
    <div style={{ minHeight: '100%', boxSizing: 'border-box', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16, background: '#17211f' }}>
      <Card style={{ width: '100%', maxWidth: 400 }} title="听这节课后台">
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          {ticket && <div style={{ fontSize: 44, fontWeight: 600, letterSpacing: 6, textAlign: 'center', fontVariantNumeric: 'tabular-nums' }}>{ticket.code.slice(0, 3)} {ticket.code.slice(3)}</div>}
          {ticket && <div style={{ ...muted, textAlign: 'center' }}>打开小程序，进入「设置 › 我的设备 › 绑定新设备」，输入这个码并允许。只有管理员账号能进入后台。</div>}
          {error && <Alert type="error" message={error} />}
          {!ticket && !error && <Spin />}
          {error && <Button type="primary" block onClick={start}>重新获取</Button>}
        </Space>
      </Card>
    </div>
  )
}

// ---- shell -------------------------------------------------------------------------------

const useHash = () => {
  const [hash, setHash] = useState(location.hash.slice(2) || 'overview')
  useEffect(() => {
    const on = () => setHash(location.hash.slice(2) || 'overview')
    addEventListener('hashchange', on)
    return () => removeEventListener('hashchange', on)
  }, [])
  return hash
}

function Console() {
  const wide = Grid.useBreakpoint().lg
  const [menuOpen, setMenuOpen] = useState(false)
  const [signedIn, setSignedIn] = useState(!!deviceToken())
  const hash = useHash()
  const [summary, setSummary] = useState(null)
  const refresh = useCallback(() => api('GET', '/summary').then(setSummary).catch(() => {}), [])
  useEffect(() => onSignedOut(() => setSignedIn(false)), [])
  useEffect(() => { if (signedIn) refresh() }, [signedIn, refresh])
  const done = useCallback(() => setSignedIn(true), [])
  if (!signedIn) return <Login onDone={done} />

  const go = (route) => { location.hash = `#/${route}` }
  const [page, arg] = hash.split('/')
  const counts = summary?.courses || {}
  const pending = (summary?.pendingCourses || 0) + (summary?.pendingReaders || 0) + (summary?.withdrawRequests || 0)
  const selected = page === 'course' ? 'courses/approved' : hash
  const titles = { overview: '概览', queue: '待处理', users: '用户与角色', devices: '设备', storage: '同步与存储', audit: '操作日志' }
  const kindName = Object.fromEntries(COURSE_KINDS)[arg]
  const title = page === 'courses' ? `课程 / ${kindName}` : page === 'course' ? '课程详情' : titles[page] || '概览'
  const brand = <div style={{ height: 56, display: 'flex', alignItems: 'center', padding: '0 20px', color: '#fff', fontSize: 16, fontWeight: 600 }}>听这节课后台</div>
  const menu = (
    <Menu theme="dark" mode="inline" selectedKeys={[selected]} defaultOpenKeys={['courses']} onClick={({ key }) => { go(key); setMenuOpen(false) }} items={[
      { key: 'overview', icon: <DashboardOutlined />, label: '概览' },
      { key: 'queue', icon: <BellOutlined />, label: <Space>待处理{pending > 0 && <Badge count={pending} size="small" />}</Space> },
      { key: 'courses', icon: <ReadOutlined />, label: '课程', children: COURSE_KINDS.map(([kind, name]) => ({ key: `courses/${kind}`, label: `${name} ${counts[kind] || 0}` })) },
      { key: 'users', icon: <TeamOutlined />, label: '用户与角色' },
      { key: 'devices', icon: <MobileOutlined />, label: '设备' },
      { key: 'storage', icon: <CloudSyncOutlined />, label: '同步与存储' },
      { key: 'audit', icon: <FileSearchOutlined />, label: '操作日志' }
    ]} />
  )
  return (
    <Layout style={{ minHeight: '100%' }}>
      {wide && <Sider width={208}>{brand}{menu}</Sider>}
      {!wide && <Drawer placement="left" width={240} open={menuOpen} onClose={() => setMenuOpen(false)} closable={false} styles={{ body: { padding: 0, background: '#001529' } }}>{brand}{menu}</Drawer>}
      <Layout style={{ minWidth: 0 }}>
        <Header style={{ background: '#fff', padding: wide ? '0 20px' : '0 8px 0 4px', height: 52, lineHeight: '52px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, borderBottom: '1px solid #dfe4e2' }}>
          <Space size={4} style={{ minWidth: 0 }}>
            {!wide && <Button type="text" aria-label="打开菜单" icon={<MenuOutlined />} style={{ width: 44, height: 44 }} onClick={() => setMenuOpen(true)} />}
            <strong style={{ fontSize: 16, whiteSpace: 'nowrap' }}>{title}</strong>
          </Space>
          <Popconfirm title="退出后台？" description="这个浏览器要重新用绑定码登录。" okText="退出" cancelText="取消" onConfirm={signOut}>
            <Button type="text" size="small" style={{ flex: 'none' }} icon={<Avatar size="small" icon={<UserOutlined />} />}>退出</Button>
          </Popconfirm>
        </Header>
        <Content style={{ padding: wide ? '16px 20px' : 12, minWidth: 0 }}>
          {page === 'queue' ? <Queue refresh={refresh} />
            : page === 'courses' && kindName ? <Courses key={arg} kind={arg} refresh={refresh} />
            : page === 'course' ? <CourseDetail key={arg} id={arg} refresh={refresh} />
            : page === 'users' ? <Users refresh={refresh} />
            : page === 'devices' ? <Devices refresh={refresh} />
            : page === 'storage' ? <Storage />
            : page === 'audit' ? <Audit />
            : <Overview go={go} summary={summary} />}
        </Content>
      </Layout>
    </Layout>
  )
}

createRoot(document.getElementById('root')).render(
  <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }} theme={{ token: { colorPrimary: '#00696b', borderRadius: 6, fontSize: 13 } }}>
    {/* Old engines lack :where() and logical properties, which Ant Design's styles lean on. */}
    <StyleProvider hashPriority="high" transformers={[legacyLogicalPropertiesTransformer]}>
      <AntApp style={{ height: '100%' }}><Console /></AntApp>
    </StyleProvider>
  </ConfigProvider>
)
