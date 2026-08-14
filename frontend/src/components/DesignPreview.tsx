import { useState } from "react";

type PreviewTab = "list" | "chat" | "profile" | "settings";

const conversations = [
  { name: "지윤:) ", preview: "오늘 저녁 몇 시에 만날까? 😊", time: "오후 8:36", unread: 2, color: "#edb37e" },
  { name: "민수", preview: "파일을 보냈습니다.", time: "오후 7:45", unread: 1, color: "#8ca5bd" },
  { name: "여행 계획 ✈️", preview: "하은: 숙소 예약 완료! 확인 부탁해", time: "오후 6:20", unread: 0, color: "#c8a56b" },
  { name: "하은", preview: "다음 주에 보자!", time: "오후 5:10", unread: 0, color: "#d8908a" },
  { name: "디자인 팀 🎨", preview: "민수: 새 디자인 가이드 공유합니다.", time: "오후 4:42", unread: 3, color: "#9c9fcd" },
  { name: "엄마", preview: "잘 지내지? 밥은 잘 챙겨 먹고!", time: "오후 2:30", unread: 0, color: "#7a9b64" },
];

function Icon({ name, size = 18 }: { name: "search" | "plus" | "phone" | "video" | "more" | "back" | "image" | "bell" | "shield" | "settings"; size?: number }) {
  const paths: Record<string, JSX.Element> = {
    search: <><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4" /></>,
    plus: <><path d="M12 5v14M5 12h14" /></>,
    phone: <path d="M6.5 4.8 9.2 4l1.8 4.1-1.7 1.5a15 15 0 0 0 5.1 5.1l1.5-1.7 4.1 1.8-.8 2.7c-.3 1.1-1.4 1.8-2.5 1.5A16.4 16.4 0 0 1 4.9 7.3c-.3-1.1.4-2.2 1.6-2.5Z" />,
    video: <><rect x="3" y="6" width="13" height="12" rx="2" /><path d="m16 10 5-3v10l-5-3" /></>,
    more: <><path d="M4 7h16M4 12h16M4 17h16" /></>,
    back: <path d="m15 5-7 7 7 7" />,
    image: <><rect x="3" y="4" width="18" height="16" rx="2" /><circle cx="8" cy="9" r="1.5" /><path d="m4 17 5-5 3 3 2-2 6 5" /></>,
    bell: <path d="M6 10a6 6 0 0 1 12 0c0 4 1.5 5.5 1.5 5.5h-15S6 14 6 10ZM10 19h4" />,
    shield: <path d="M12 3 19 6v5c0 4.5-2.9 8.1-7 10-4.1-1.9-7-5.5-7-10V6l7-3Z" />,
    settings: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-1.7 1.7-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-2.4v-.2a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L8 17l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.6-1H6v-2.4h.2a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L7.4 8.6 9.1 7l.1.1a1.7 1.7 0 0 0 1.9.3 1.7 1.7 0 0 0 1-1.6v-.2h2.4v.2a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1 1.7 1.7-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v2.4h-.2a1.7 1.7 0 0 0-1.6 1Z" /></>,
  };
  return <svg className="preview-icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>{paths[name]}</svg>;
}

function PreviewAvatar({ label, color, large = false }: { label: string; color: string; large?: boolean }) {
  return <div className={`preview-avatar ${large ? "preview-avatar-large" : ""}`} style={{ background: `linear-gradient(135deg, ${color}, #9b8fe8)` }}>{label.slice(0, 1)}</div>;
}

export default function DesignPreview() {
  const [tab, setTab] = useState<PreviewTab>("list");
  return (
    <div className="family-preview">
      <div className="preview-topbar">
        <div className="preview-brand"><span className="preview-brand-mark">S</span><span>SecureMsg</span></div>
        <span className="preview-badge">패밀리룩 미리보기</span>
        <a href="/" className="preview-backlink">실제 앱 열기</a>
      </div>
      <div className="preview-board">
        <aside className="preview-list-panel">
          <div className="preview-list-heading"><h1>채팅</h1><button aria-label="새 대화"><Icon name="plus" size={20} /></button></div>
          <div className="preview-search"><Icon name="search" size={17} /><span>검색</span></div>
          <div className="preview-filters"><span className="active">전체</span><span>안 읽은 메시지 <b>3</b></span><span>즐겨찾기</span><span>그룹</span></div>
          <div className="preview-conversation-list">
            {conversations.map((item) => <button key={item.name} className={`preview-conversation ${item.name.startsWith("지윤") ? "selected" : ""}`} onClick={() => setTab("chat")}>
              <PreviewAvatar label={item.name} color={item.color} />
              <span className="preview-conversation-copy"><strong>{item.name}</strong><small>{item.preview}</small></span>
              <span className="preview-conversation-meta"><time>{item.time}</time>{item.unread > 0 && <b>{item.unread}</b>}</span>
            </button>)}
          </div>
          <nav className="preview-bottom-nav"><button className="active" onClick={() => setTab("list")}>●<span>채팅</span></button><button>♙<span>연락처</span></button><button onClick={() => setTab("settings")}>⚙<span>설정</span></button></nav>
        </aside>

        {tab === "chat" ? <PreviewChat onBack={() => setTab("list")} onProfile={() => setTab("profile")} /> : tab === "profile" ? <PreviewProfile onBack={() => setTab("chat")} /> : tab === "settings" ? <PreviewSettings onBack={() => setTab("list")} /> : <div className="preview-empty"><span className="preview-empty-icon">✦</span><strong>대화를 선택하세요</strong><small>왼쪽 목록에서 대화를 선택하면<br />안전하게 암호화된 대화를 시작할 수 있어요.</small></div>}
      </div>
    </div>
  );
}

function PreviewChat({ onBack, onProfile }: { onBack: () => void; onProfile: () => void }) {
  return <section className="preview-chat-panel">
    <header className="preview-chat-header"><button onClick={onBack} aria-label="목록으로"><Icon name="back" /></button><button className="preview-contact-button" onClick={onProfile}><PreviewAvatar label="지윤" color="#edb37e" /><span><strong>지윤:)</strong><small>온라인 · E2E 암호화</small></span></button><div className="preview-header-actions"><button><Icon name="phone" /></button><button><Icon name="video" /></button><button onClick={onProfile} aria-label="대화 설정"><Icon name="more" /></button></div></header>
    <div className="preview-chat-wallpaper"><span className="preview-day">오늘</span><div className="preview-bubble incoming">오늘 저녁 몇 시에 만날까? 😊<small>오후 8:31</small></div><div className="preview-bubble outgoing">나 지금 괜찮아!<small>오후 8:32 ✓✓</small></div><div className="preview-voice"><span className="preview-play">▶</span><span className="preview-wave">▮▮▮▮▮▮▮▮▮▮▮▮</span><strong>0:12</strong><small>오후 8:33</small></div><div className="preview-photo"><div className="preview-photo-art">서울의 밤</div><small>오후 8:34 ✓✓</small></div><div className="preview-bubble outgoing">여기 어때? 뷰가 너무 예뻐 보여! 😍<small>오후 8:35 ✓✓</small></div><div className="preview-bubble incoming">완전 좋아! 그럼 여기로 🙌<small>오후 8:35</small></div></div>
    <form className="preview-composer" onSubmit={(event) => event.preventDefault()}><button type="button" className="preview-composer-plus">+</button><input placeholder="메시지 입력" /><button type="submit" className="preview-send">↑</button></form>
  </section>;
}

function PreviewProfile({ onBack }: { onBack: () => void }) {
  return <section className="preview-profile-panel"><header className="preview-profile-header"><button onClick={onBack} aria-label="뒤로"><Icon name="back" /></button><button>편집</button></header><div className="preview-profile-hero"><PreviewAvatar label="지윤" color="#edb37e" large /><h2>지윤:)</h2><small>SecureMsg 연락처</small></div><div className="preview-profile-actions"><button><Icon name="phone" /><span>전화</span></button><button><Icon name="video" /><span>영상 통화</span></button><button><Icon name="search" /><span>검색</span></button><button><Icon name="more" /><span>더보기</span></button></div><div className="preview-profile-card"><h3><Icon name="image" /> 미디어, 링크 및 파일 <span>142 ›</span></h3><div className="preview-media-strip"><i>서울</i><i>☕</i><i>바다</i><i>여행</i><i>+138</i></div></div><div className="preview-profile-card preview-settings-card"><button><Icon name="bell" /><span>알림</span><em>켜짐 ›</em></button><button><Icon name="shield" /><span>개인정보 보호</span><em>›</em></button><button><Icon name="settings" /><span>배경화면 및 테마</span><em>›</em></button><button className="danger"><span>▣</span><span>대화 내용 삭제</span></button></div></section>;
}

function PreviewSettings({ onBack }: { onBack: () => void }) {
  return <section className="preview-settings-panel"><header className="preview-profile-header"><button onClick={onBack} aria-label="뒤로"><Icon name="back" /></button><strong>설정</strong><span className="preview-header-placeholder" /></header><div className="preview-settings-scroll"><div className="preview-settings-group"><PreviewToggle icon="shield" label="알 수 없는 발신자 필터" enabled /><PreviewToggle icon="image" label="MMS 자동 다운로드" enabled /><PreviewToggle icon="bell" label="읽지 않은 메시지만 보기" /></div><div className="preview-settings-group"><PreviewSettingsRow icon="shield" label="스팸 차단 및 관리" /><PreviewSettingsRow label="AA" labelClass="text-icon" labelText="글자 크기" value="보통" /><PreviewSettingsRow icon="settings" label="배경 테마" value="기본" /></div><div className="preview-settings-group"><PreviewSettingsRow icon="image" label="메시지 백업" /><PreviewSettingsRow icon="shield" label="차단된 연락처" /><PreviewSettingsRow icon="settings" label="SIM 메시지 관리" /></div><div className="preview-settings-group"><PreviewSettingsRow icon="shield" label="앱 정보" /></div><button className="preview-delete-all">▥ <span>모든 대화 삭제</span></button></div></section>;
}

function PreviewToggle({ icon, label, enabled = false }: { icon: "shield" | "image" | "bell"; label: string; enabled?: boolean }) {
  return <div className="preview-settings-row"><Icon name={icon} /><span>{label}</span><i className={`preview-toggle ${enabled ? "on" : ""}`}><b /></i></div>;
}

function PreviewSettingsRow({ icon, label, value, labelClass, labelText }: { icon?: "shield" | "image" | "settings"; label: string; value?: string; labelClass?: string; labelText?: string }) {
  return <div className="preview-settings-row">{icon ? <Icon name={icon} /> : <span className={labelClass}>{labelText ?? label}</span>}<span>{label}</span><em>{value ?? "›"}</em></div>;
}
