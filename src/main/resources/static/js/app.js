/* ==========================================================================
   通用交互：API 调用（JWT）、Toast、弹窗、动作按钮、移动端导航、打赏
   纯原生 JS，不依赖任何框架，全部本地资源。
   后端统一返回 Result：{ code:200, message, data }
   ========================================================================== */
(function () {
  'use strict';

  var TOKEN_KEY = 'jargus_token';

  /* 布局里把解析好的 i18n 文案挂在这里 */
  var messages = window.JargusMessages || {};

  function t(key) {
    if (!key) return '';
    return messages[key] || key;
  }

  function getToken() {
    try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }
  function setToken(token) {
    try { localStorage.setItem(TOKEN_KEY, token); } catch (e) { /* 隐私模式 */ }
  }
  function clearToken() {
    try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* ignore */ }
  }

  /* ─── 小工具 ────────────────────────────────────────────── */

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function isLoginPage() {
    return location.pathname === '/login';
  }

  /** 401：会话失效，回登录页并带上回跳地址（登录页本身不再跳，避免循环） */
  function redirectToLogin() {
    if (isLoginPage()) return;
    var here = location.pathname + location.search;
    location.href = '/login?redirect=' + encodeURIComponent(here);
  }

  /* ─── Toast ─────────────────────────────────────────────── */

  var ICONS = {
    ok: 'check-circle-fill',
    success: 'check-circle-fill',
    danger: 'x-circle-fill',
    error: 'x-circle-fill',
    warn: 'exclamation-triangle-fill',
    warning: 'exclamation-triangle-fill',
    info: 'info-circle-fill'
  };

  /** 构造引用内联 SVG 精灵表的图标（精灵表随页面内联，<use> 不产生额外请求） */
  function icon(name, cls) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
    var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#' + name);
    svg.appendChild(use);
    return svg;
  }

  function iconHtml(name, cls) {
    return '<svg class="ico' + (cls ? ' ' + cls : '') + '"><use href="#' + name + '"/></svg>';
  }

  function toast(message, kind) {
    var box = document.getElementById('toasts');
    if (!box) return;

    kind = kind || 'info';
    var el = document.createElement('div');
    el.className = 'toast toast-' + kind;

    el.appendChild(icon(ICONS[kind] || ICONS.info));
    var body = document.createElement('div');
    body.className = 'toast-msg';
    body.textContent = message;           // textContent：不把服务端消息当 HTML 执行
    var close = document.createElement('button');
    close.type = 'button';
    close.className = 'alert-close';
    close.innerHTML = iconHtml('x-lg');

    el.appendChild(body);
    el.appendChild(close);
    box.appendChild(el);

    var timer = setTimeout(dismiss, kind === 'danger' ? 8000 : 4000);
    close.addEventListener('click', function () {
      clearTimeout(timer);
      dismiss();
    });

    function dismiss() {
      el.classList.add('out');
      setTimeout(function () { el.remove(); }, 220); // 等退场动画结束再移除
    }
  }

  /* ─── API 调用 ──────────────────────────────────────────── */

  /**
   * 统一 fetch：
   * - 自动带 JWT（localStorage 里的 Bearer；同源时 httpOnly cookie 也会带上）
   * - 解析 Result 信封：code===200  resolve(data)，否则 reject(Error(message))
   * - 401 回登录页
   * body 为对象时按 JSON 发送；FormData 直接透传（multipart）
   */
  function request(method, url, body) {
    var headers = { 'Accept': 'application/json' };
    var token = getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;

    var payload = null;
    if (body !== undefined && body !== null) {
      if (body instanceof FormData) {
        payload = body; // 浏览器自己加 Content-Type（含 boundary）
      } else {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
      }
    }

    return fetch(url, {
      method: method,
      headers: headers,
      body: payload,
      credentials: 'same-origin'
    }).then(function (response) {
      if (response.status === 401) {
        redirectToLogin();
        throw new Error(t('error.sessionExpired'));
      }
      return response.json().catch(function () {
        throw new Error('HTTP ' + response.status);
      }).then(function (json) {
        if (json && (json.code === 200 || json.code === 0 || json.success === true)) {
          return json.data !== undefined ? json.data : json;
        }
        var msg = (json && json.message) ? json.message : ('HTTP ' + response.status);
        var err = new Error(msg);
        err.payload = json;
        throw err;
      });
    });
  }

  var api = {
    get: function (url) { return request('GET', url); },
    post: function (url, body) { return request('POST', url, body || {}); },
    put: function (url, body) { return request('PUT', url, body || {}); },
    del: function (url) { return request('DELETE', url); },
    upload: function (url, formData) { return request('POST', url, formData); }
  };

  /**
   * 通用动作按钮：data-api=URL  data-method=POST|PUT|DELETE  data-confirm=...
   * data-body='JSON 字符串'（可省略）  data-reload="true"  data-busy-text=...
   * data-toggle-class / data-toggle-text 用于「启用/停用」类按钮即时换态
   */
  function bindActionButton(btn) {
    btn.addEventListener('click', function (e) {
      // 开关（checkbox）依赖浏览器原生翻转做即时反馈：不阻止默认行为，
      // 请求失败时再翻回去，避免"提示成功但状态不刷新就不变"的错觉
      var isSwitch = btn.tagName === 'INPUT' && btn.type === 'checkbox';
      if (!isSwitch) e.preventDefault();
      if (btn.dataset.confirm && !window.confirm(btn.dataset.confirm)) {
        if (isSwitch) btn.checked = !btn.checked;
        return;
      }

      var original = btn.innerHTML;
      btn.disabled = true;
      if (!isSwitch) {
        btn.innerHTML = '<span class="spin"></span>' +
          (btn.dataset.busyText ? ' ' + btn.dataset.busyText : '');
      }

      var body;
      if (btn.dataset.body) {
        try { body = JSON.parse(btn.dataset.body); } catch (err) { body = {}; }
      }
      var promise = request(btn.dataset.method || 'POST', btn.dataset.api, body);

      promise.then(function () {
        toast(btn.dataset.okText || t('common.done'), 'ok');
        if (btn.dataset.reload === 'true') {
          setTimeout(function () { window.location.reload(); }, 700);
          return;
        }
        if (btn.dataset.redirect) {
          setTimeout(function () { window.location.href = btn.dataset.redirect; }, 700);
          return;
        }
        btn.disabled = false;
        if (!isSwitch) btn.innerHTML = original;
      }).catch(function (err) {
        // 开关翻回原状态，让界面与服务器保持一致
        if (isSwitch) btn.checked = !btn.checked;
        toast(String(err && err.message || err), 'danger');
        btn.disabled = false;
        if (!isSwitch) btn.innerHTML = original;
      });
    });
  }

  /* ─── 通用弹窗（模态） ──────────────────────────────────── */

  function openModal(id) {
    var modal = document.getElementById(id);
    if (!modal) return;
    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
    var autofocus = modal.querySelector('[autofocus], input:not([type=hidden]), select, textarea');
    if (autofocus) setTimeout(function () { autofocus.focus(); }, 50);
  }

  function closeModal(id) {
    var modal;
    if (id) {
      modal = document.getElementById(id);
    } else {
      // 未指定时关闭最上层（DOM 中最后一个）打开的弹窗，支持弹窗叠弹窗
      var opens = document.querySelectorAll('.modal.open');
      modal = opens.length ? opens[opens.length - 1] : null;
    }
    if (!modal) return;
    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = document.querySelector('.modal.open') ? 'hidden' : '';
  }

  function bindModals() {
    document.querySelectorAll('[data-open-modal]').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        openModal(btn.dataset.openModal);
      });
    });
    document.querySelectorAll('[data-close-modal]').forEach(function (el) {
      el.addEventListener('click', function (e) {
        e.preventDefault();
        closeModal(el.dataset.closeModal || undefined);
      });
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeModal();
    });
    // 点遮罩空白处关闭
    document.querySelectorAll('.modal').forEach(function (modal) {
      modal.addEventListener('click', function (e) {
        if (e.target === modal) closeModal(modal.id);
      });
    });
  }

  /* ─── JSON 表单：收集后发请求，关闭弹窗/刷新/跳转 ─────────── */

  /**
   * form 上声明：data-api（可含 {id} 占位） data-method
   * 成功后：data-reload / data-close / data-redirect
   */
  function bindJsonForm(form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      if (form.dataset.confirm && !window.confirm(form.dataset.confirm)) return;

      var submitBtn = form.querySelector('[type=submit]');
      var original = submitBtn ? submitBtn.innerHTML : '';
      if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<span class="spin"></span> ' + (form.dataset.busyText || '');
      }

      var isMultipart = form.enctype === 'multipart/form-data';
      var body;
      if (isMultipart) {
        body = new FormData(form);
      } else {
        body = {};
        Array.prototype.forEach.call(form.elements, function (el) {
          if (!el.name || el.disabled) return;
          if (el.type === 'checkbox') {
            body[el.name] = el.checked;
          } else if (el.type === 'number') {
            body[el.name] = el.value === '' ? null : Number(el.value);
          } else {
            body[el.name] = el.value;
          }
        });
      }

      var url = form.dataset.api;
      request(form.dataset.method || 'POST', url, body)
        .then(function (data) {
          toast(form.dataset.okText || t('common.saved'), 'ok');
          if (form.dataset.redirect) {
            var target = form.dataset.redirect;
            if (data && typeof data === 'object' && data.id != null) {
              target = target.replace('{id}', encodeURIComponent(data.id));
            }
            setTimeout(function () { window.location.href = target; }, 700);
            return;
          }
          if (form.dataset.close) closeModal(form.dataset.close);
          if (form.dataset.reload === 'true') {
            setTimeout(function () { window.location.reload(); }, 700);
            return;
          }
          if (!form.dataset.keepOpen) form.reset();
          if (submitBtn) { submitBtn.disabled = false; submitBtn.innerHTML = original; }
        })
        .catch(function (err) {
          toast(String(err && err.message || err), 'danger');
          if (submitBtn) { submitBtn.disabled = false; submitBtn.innerHTML = original; }
        });
    });
  }

  /* ─── 移动端导航抽屉 ────────────────────────────────────── */

  function bindNavToggle(btn) {
    var links = document.getElementById('nav-links');
    if (!links) return;
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = links.classList.toggle('open');
      btn.innerHTML = open ? iconHtml('x-lg') : iconHtml('list');
    });
    document.addEventListener('click', function (e) {
      if (!links.classList.contains('open')) return;
      if (!links.contains(e.target) && !btn.contains(e.target)) {
        links.classList.remove('open');
        btn.innerHTML = iconHtml('list');
      }
    });
  }

  /* ─── 顶部导航下拉分组（桌面悬停 + 点击；移动端手风琴） ─── */

  function closeNavGroups(except) {
    document.querySelectorAll('.nav-group.open').forEach(function (g) {
      if (g !== except) {
        g.classList.remove('open');
        var t = g.querySelector('.nav-trigger');
        if (t) t.setAttribute('aria-expanded', 'false');
      }
    });
  }

  function bindNavGroups() {
    var groups = document.querySelectorAll('.nav-group');
    if (!groups.length) return;
    groups.forEach(function (group) {
      var trigger = group.querySelector('.nav-trigger');
      if (!trigger) return;
      trigger.addEventListener('click', function (e) {
        e.stopPropagation();
        var willOpen = !group.classList.contains('open');
        closeNavGroups(group);
        group.classList.toggle('open', willOpen);
        trigger.setAttribute('aria-expanded', String(willOpen));
      });
      // 桌面鼠标移出后清掉点击留下的展开态，避免再次悬停状态错乱
      group.addEventListener('mouseleave', function () {
        group.classList.remove('open');
        trigger.setAttribute('aria-expanded', 'false');
      });
      // 选中菜单项（打开弹窗/跳转）后收起下拉
      group.querySelectorAll('.menu-item').forEach(function (item) {
        item.addEventListener('click', function () {
          closeNavGroups();
        });
      });
    });
    // 点击菜单外 / Esc 收起
    document.addEventListener('click', function (e) {
      if (!e.target.closest || !e.target.closest('.nav-group')) closeNavGroups();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeNavGroups();
    });
  }

  /* ─── 密码框显示/隐藏 ───────────────────────────────────── */

  /** 只切 type，不把明文写进任何变量或 DOM 文本节点 */
  function bindPasswordToggle(btn) {
    var input = document.getElementById(btn.dataset.target);
    if (!input) return;
    btn.addEventListener('click', function () {
      var shown = input.type === 'text';
      input.type = shown ? 'password' : 'text';
      btn.classList.toggle('active', !shown);
      input.focus();
    });
  }

  /* ─── 默认口令横幅 ──────────────────────────────────────── */

  /**
   * 关掉「仍在用默认口令」的横幅。记 sessionStorage：本次会话有效，
   * 浏览器关了下次继续提示；口令真的改了之后服务端不再渲染这条横幅。
   */
  function bindDefaultPwDismiss(btn) {
    btn.addEventListener('click', function () {
      var banner = btn.closest('.default-pw-banner');
      if (banner) banner.remove();
      try {
        sessionStorage.setItem('jargus-pw-banner-dismissed', '1');
      } catch (e) { /* 隐私模式：本次点击仍生效 */ }
    });
  }

  /* ─── 打赏弹窗 ──────────────────────────────────────────── */

  /** 图片文件名与 data-pay 同名（wechat/alipay/qq），文案在 data-hint 上 */
  function bindDonate() {
    var modal = document.getElementById('donate-modal');
    if (!modal) return;
    var assets = modal.dataset.assets || '/assets/';

    function open() {
      modal.classList.add('open');
      modal.setAttribute('aria-hidden', 'false');
      document.body.style.overflow = 'hidden';
    }
    function close() {
      modal.classList.remove('open');
      modal.setAttribute('aria-hidden', 'true');
      document.body.style.overflow = '';
    }

    document.querySelectorAll('[data-role="open-donate"]').forEach(function (btn) {
      btn.addEventListener('click', open);
    });
    document.querySelectorAll('[data-role="close-donate"]').forEach(function (el) {
      el.addEventListener('click', close);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && modal.classList.contains('open')) close();
    });

    var tabs = modal.querySelector('[data-role="pay-tabs"]');
    if (!tabs) return;
    var qr = document.getElementById('donate-qr');
    var tip = document.getElementById('donate-tip');

    tabs.addEventListener('click', function (e) {
      var btn = e.target.closest('.pay-tab');
      if (!btn) return;
      if (qr) {
        qr.src = assets + btn.dataset.pay + '.webp';
        qr.alt = btn.textContent.trim();
      }
      if (tip && btn.dataset.hint) tip.textContent = btn.dataset.hint;
      tabs.querySelectorAll('.pay-tab').forEach(function (b) {
        b.classList.toggle('active', b === btn);
      });
    });
  }

  /* ─── 初始化 ────────────────────────────────────────────── */

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-api-role="action"]').forEach(bindActionButton);
    document.querySelectorAll('form[data-api]').forEach(bindJsonForm);
    document.querySelectorAll('[data-role="nav-toggle"]').forEach(bindNavToggle);
    bindNavGroups();
    document.querySelectorAll('[data-role="toggle-password"]').forEach(bindPasswordToggle);
    document.querySelectorAll('[data-role="dismiss-default-pw"]').forEach(bindDefaultPwDismiss);
    bindModals();
    bindDonate();

    // 关闭提示条
    document.querySelectorAll('[data-dismiss]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var alert = btn.closest('.alert');
        if (alert) alert.remove();
      });
    });
    // 成功提示自动消失；错误提示留着让用户看清
    document.querySelectorAll('.alert-ok[data-auto-dismiss]').forEach(function (alert) {
      setTimeout(function () { alert.remove(); }, 5000);
    });
  });

  /* 对外工具（各页面内联脚本使用） */
  window.JargusUI = {
    t: t,
    esc: esc,
    icon: icon,
    iconHtml: iconHtml,
    toast: toast,
    api: api,
    request: request,
    openModal: openModal,
    closeModal: closeModal,
    token: { get: getToken, set: setToken, clear: clearToken }
  };
})();
