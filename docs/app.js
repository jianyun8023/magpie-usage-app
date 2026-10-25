"use strict";

// Without JavaScript, all three feature sections remain readable.
const tabs = [...document.querySelectorAll("[data-tab]")];
const panels = [...document.querySelectorAll("[data-panel]")];
const tabList = document.querySelector(".feature-tabs");

function selectTab(selected) {
  for (const tab of tabs) {
    const active = tab === selected;
    tab.setAttribute("aria-selected", String(active));
    tab.tabIndex = active ? 0 : -1;
  }
  for (const panel of panels) {
    panel.hidden = panel.dataset.panel !== selected.dataset.tab;
  }
}

if (tabList && tabs.length && panels.length) {
  for (const panel of panels) {
    panel.setAttribute("role", "tabpanel");
    panel.setAttribute("aria-labelledby", `tab-${panel.dataset.panel}`);
    panel.tabIndex = 0;
  }
  for (const [index, tab] of tabs.entries()) {
    tab.addEventListener("click", () => selectTab(tab));
    tab.addEventListener("keydown", (event) => {
      let next;
      if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
      if (event.key === "ArrowLeft") next = (index + tabs.length - 1) % tabs.length;
      if (event.key === "Home") next = 0;
      if (event.key === "End") next = tabs.length - 1;
      if (next === undefined) return;
      event.preventDefault();
      selectTab(tabs[next]);
      tabs[next].focus();
    });
  }
  selectTab(tabs[0]);
  document.querySelector(".features").classList.add("tabs-ready");
  tabList.hidden = false;
}

const demoButton = document.querySelector(".demo-toggle");
const demoImage = document.getElementById("demo-image");
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

if (demoButton && demoImage) {
  let playing = false;
  function setPlaying(value) {
    playing = value;
    // The GIF is fetched only after an explicit click, even with reduced motion.
    demoImage.src = value ? "assets/overview.gif" : "assets/usage.png";
    demoImage.alt = value ? "Magpie用量界面轮播演示，演示数据，打码示例账号" : "Magpie用量客户端的用量汇总界面";
    demoButton.setAttribute("aria-pressed", String(value));
    demoButton.textContent = value ? "停止演示 ■" : "播放界面演示 ▶";
  }
  demoButton.hidden = false;
  demoButton.addEventListener("click", () => setPlaying(!playing));
  reducedMotion.addEventListener("change", () => {
    if (reducedMotion.matches && playing) setPlaying(false);
  });
  document.addEventListener("visibilitychange", () => {
    if (document.hidden && playing) setPlaying(false);
  });
}
