/* Bundled app code, never loaded from an EPUB or a remote server. */
(() => {
    'use strict';
    const content = document.getElementById('__moonlightPages');
    const nodes = [];
    const walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
        if (walker.currentNode.data.trim()) nodes.push(walker.currentNode);
    }
    let page = 0, pages = 1, width = 1, focus = null;
    let highlights = [], paintedRects = [];
    let appearanceKey = '';
    // Room the page keeps from the screen's edges, in pixels, and the face and spacing it is set in.
    let inset = 24;
    const anchorCache = new Map();
    const palette = {YELLOW:'rgba(242,204,96,.48)', MINT:'rgba(138,201,165,.48)',
        ROSE:'rgba(237,164,181,.48)', LAVENDER:'rgba(185,167,228,.48)'};
    const nativeHighlights = typeof CSS !== 'undefined' && CSS.highlights && typeof window.Highlight === 'function';
    const overlay = document.createElement('div');
    overlay.id = '__moonlightHighlights';
    overlay.style.cssText = 'position:absolute;inset:0;overflow:hidden;pointer-events:none;z-index:2';
    document.getElementById('__moonlightViewport').appendChild(overlay);
    const highlightStyle = document.createElement('style');
    highlightStyle.textContent = Object.entries(palette).map(([name,color]) =>
        `::highlight(__moonlight${name}) { background-color:${color}; }`).join('\n');
    document.head.appendChild(highlightStyle);
    const range = document.createRange();
    const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
    function rectAt(node, offset) {
        if (!node || !node.length) return null;
        const start = clamp(offset, 0, node.length - 1);
        range.setStart(node, start);
        range.setEnd(node, start + 1);
        return range.getClientRects()[0] || null;
    }
    function column(rect) {
        return Math.floor((rect.left + page * width - 24 + 0.5) / width);
    }
    function anchor(node, offset) {
        return {node, offset, quote:nodes[node].data.slice(offset, offset + 48)};
    }
    function valid(value) {
        return value && Number.isInteger(value.node) && Number.isInteger(value.offset) &&
            nodes[value.node] && value.offset >= 0 && value.offset < nodes[value.node].length &&
            (!value.quote || nodes[value.node].data.slice(value.offset, value.offset + value.quote.length) === value.quote);
    }
    // Character columns are monotonic even when lines wrap; horizontal pixel coordinates are not.
    function lowerBound(node, target) {
        let low = 0, high = node.length;
        while (low < high) {
            const middle = (low + high) >>> 1;
            const rect = rectAt(node, middle);
            if (!rect || column(rect) < target) low = middle + 1;
            else high = middle;
        }
        return low;
    }
    function visibleAnchors() {
        if (anchorCache.has(page)) return anchorCache.get(page);
        let first = null, last = null;
        for (let index = 0; index < nodes.length; index++) {
            const node = nodes[index];
            range.selectNodeContents(node);
            const visible = Array.from(range.getClientRects()).some(rect =>
                rect.width > 0 && rect.right > 24.5 && rect.left < width - 24.5 &&
                rect.bottom > 20 && rect.top < window.innerHeight - 20);
            if (!visible) continue;
            const start = lowerBound(node, page);
            const end = lowerBound(node, page + 1);
            if (start >= node.length) continue;
            if (!first) first = anchor(index, start);
            last = {node:index, offset:end, quote:''};
        }
        const anchors = {start:first, end:last};
        anchorCache.set(page, anchors);
        return anchors;
    }
    function measure() {
        anchorCache.clear();
        width = Math.max(1, window.innerWidth);
        // WebView can retain a zero-height CSS vh viewport after its first Android measure.
        // Set the column box from the actual viewport instead of relying on vh units alone.
        const room = inset * 2;
        const tall = Math.round(inset * 5 / 6) * 2;
        const height = Math.max(80, window.innerHeight - tall);
        content.style.setProperty('width', `${Math.max(80, width - room)}px`, 'important');
        content.style.setProperty('height', `${height}px`, 'important');
        content.style.setProperty('column-width', `${Math.max(80, width - room)}px`, 'important');
        content.querySelectorAll('img, svg').forEach(image => {
            image.style.setProperty('max-height', `${height}px`, 'important');
        });
        pages = Math.max(1, Math.ceil((content.scrollWidth + room) / width - 0.01));
    }
    function show(next) {
        page = clamp(next, 0, pages - 1);
        // Discrete pages do not need a huge GPU-promoted 3D layer. Image-heavy chapters
        // can make that layer cover/flicker over neighbouring Android/Compose controls.
        content.style.setProperty('left', `${inset - page * width}px`, 'important');
        window.scrollTo(0, 0);
        renderHighlights();
    }
    function snapshot() {
        const visible = visibleAnchors();
        if (!valid(focus)) focus = visible.start;
        // A chapter that fits on one page is at both its start and its end, so it reports 1.
        return {page, pages, fraction:pages > 1 ? page / (pages - 1) : 1,
            anchor:focus, start:visible.start, end:visible.end};
    }
    function elementFor(fragment) {
        if (!fragment) return null;
        let id;
        try { id = decodeURIComponent(fragment); } catch (_) { id = fragment; }
        return document.getElementById(id) || Array.from(document.getElementsByName(id))[0];
    }
    function locate(target) {
        if (target.fragment) {
            const element = elementFor(target.fragment);
            if (element && content.contains(element)) {
                const rect = element.getBoundingClientRect();
                return clamp(column(rect), 0, pages - 1);
            }
        }
        if (valid(target.anchor)) {
            const rect = rectAt(nodes[target.anchor.node], target.anchor.offset);
            if (rect) return clamp(column(rect), 0, pages - 1);
        }
        return clamp(Math.round((target.fraction || 0) * (pages - 1)), 0, pages - 1);
    }
    /**
     * Where the book's own chapters begin inside this file, so the reader can say which chapter it
     * is on and how much of it is left, instead of counting to the end of the whole file. Anchors
     * that this file does not carry are left out rather than guessed at.
     */
    function chapterPages(list) {
        const placed = [];
        for (const item of list || []) {
            const element = elementFor(item.fragment);
            if (!element || !content.contains(element)) continue;
            placed.push({id:item.id, page:clamp(column(element.getBoundingClientRect()), 0, pages - 1)});
        }
        return placed;
    }
    function restore(target) {
        page = 0;
        content.style.setProperty('left', `${inset}px`, 'important');
        measure();
        show(locate(target));
        focus = valid(target.anchor) ? target.anchor : null;
        return snapshot();
    }
    function rangeFor(mark) {
        if (!valid(mark.start) || !mark.end || !nodes[mark.end.node] || mark.end.offset < 0 ||
            mark.end.offset > nodes[mark.end.node].length || mark.start.node > mark.end.node ||
            (mark.start.node === mark.end.node && mark.start.offset >= mark.end.offset)) return null;
        if (mark.end.quote && nodes[mark.end.node].data.slice(mark.end.offset, mark.end.offset + mark.end.quote.length) !== mark.end.quote) return null;
        const selected = document.createRange();
        selected.setStart(nodes[mark.start.node], mark.start.offset);
        selected.setEnd(nodes[mark.end.node], mark.end.offset);
        return selected.toString().trim() === mark.text ? selected : null;
    }
    function renderHighlights() {
        paintedRects = [];
        while (overlay.firstChild) overlay.removeChild(overlay.firstChild);
        const groups = {};
        Object.keys(palette).forEach(name => { groups[name] = []; });
        // The note line under a passage is drawn in the paper's own ink, so it reads on every paper.
        const inkColor = getComputedStyle(content).color || 'currentColor';
        for (const mark of highlights) {
            if (!palette[mark.color]) continue;
            const selected = rangeFor(mark);
            if (!selected) continue; // Never paint a mismatched quote over different words.
            groups[mark.color].push(selected);
            const rects = Array.from(selected.getClientRects());
            for (const rect of rects) {
                const left = Math.max(24, rect.left), right = Math.min(width - 24, rect.right);
                const top = Math.max(20, rect.top), bottom = Math.min(innerHeight - 20, rect.bottom);
                if (right <= left || bottom <= top) continue;
                paintedRects.push({id:mark.id, left, right, top, bottom});
                if (!nativeHighlights) {
                    const wash = document.createElement('div');
                    wash.style.cssText = `position:absolute;left:${left}px;top:${top}px;width:${right-left}px;height:${bottom-top}px;background:${palette[mark.color]};border-radius:2px`;
                    overlay.appendChild(wash);
                }
                // A passage the reader wrote about keeps a line under it, so their notes are visible
                // on the page and not only in the notebook.
                if (mark.note) {
                    const rule = document.createElement('div');
                    rule.style.cssText = `position:absolute;left:${left}px;top:${bottom-2}px;width:${right-left}px;height:2px;background:${inkColor};border-radius:1px;opacity:.55`;
                    overlay.appendChild(rule);
                }
            }
        }
        if (nativeHighlights) for (const [name, ranges] of Object.entries(groups)) {
            CSS.highlights.set(`__moonlight${name}`, new window.Highlight(...ranges));
        }
        return {painted:paintedRects.length};
    }
    function selectedPassage() {
        const selection = window.getSelection();
        if (!selection || selection.isCollapsed || !selection.rangeCount) return null;
        const selected = selection.getRangeAt(0);
        let start = null, end = null;
        for (let index = 0; index < nodes.length; index++) {
            const node = nodes[index];
            if (!selected.intersectsNode(node)) continue;
            const from = selected.startContainer === node ? selected.startOffset : 0;
            const to = selected.endContainer === node ? selected.endOffset : node.length;
            if (to <= from) continue;
            if (!start) start = anchor(index, from);
            end = anchor(index, to);
        }
        if (!start || !end) return null;
        const normalized = document.createRange();
        normalized.setStart(nodes[start.node], start.offset);
        normalized.setEnd(nodes[end.node], end.offset);
        const text = normalized.toString().trim();
        if (!text) return null;
        if (text.length > 10000) return {error:'Please select no more than 10,000 characters at once.'};
        return {start, end, text, fraction:pages > 1 ? page / (pages - 1) : 0};
    }
    function styleAppearance(value) {
        if (!value) return false;
        const key = `${value.fontSize}|${value.background}|${value.ink}|${value.lineHeight}|${value.inset}|${value.fontFamily}`;
        if (key === appearanceKey) return false;
        appearanceKey = key;
        for (const element of [document.documentElement, document.body]) {
            element.style.setProperty('background-color', value.background, 'important');
            element.style.setProperty('color', value.ink, 'important');
        }
        content.style.setProperty('font-size', `${value.fontSize}px`, 'important');
        content.style.setProperty('color', value.ink, 'important');
        if (value.lineHeight) content.style.setProperty('line-height', `${value.lineHeight}`, 'important');
        if (value.fontFamily) content.style.setProperty('font-family', value.fontFamily, 'important');
        if (value.inset) {
            inset = value.inset;
            const tall = Math.round(inset * 5 / 6);
            content.style.setProperty('left', `${inset}px`, 'important');
            content.style.setProperty('top', `${tall}px`, 'important');
            content.style.setProperty('column-gap', `${inset * 2}px`, 'important');
        }
        return true;
    }
    const api = {
        ready:false,
        initialize(target) {
            highlights = target.highlights || [];
            (document.fonts ? document.fonts.ready : Promise.resolve()).then(() => {
                requestAnimationFrame(() => {
                    styleAppearance(target.appearance);
                    restore(target);
                    api.ready = true;
                });
            });
        },
        snapshot,
        turn(delta) {
            window.getSelection().removeAllRanges();
            if (page + delta < 0 || page + delta >= pages) return {boundary:delta};
            show(page + delta);
            focus = null;
            return snapshot();
        },
        relayout() {
            const target = snapshot();
            return restore(target);
        },
        appearance(value) {
            const target = snapshot();
            window.getSelection().removeAllRanges();
            return styleAppearance(value) ? restore(target) : target;
        },
        fragment(value) { window.getSelection().removeAllRanges(); return restore({fragment:value}); },
        chapterPages,
        selection:selectedPassage,
        clearSelection() { window.getSelection().removeAllRanges(); },
        setHighlights(marks) { highlights = marks; return renderHighlights(); },
        hitHighlight(x, y) {
            const element = document.elementFromPoint(x, y);
            const link = !!(element && element.closest && element.closest('a[href]'));
            const selection = window.getSelection();
            // A live text selection owns the tap, so the reader leaves it alone.
            const choosing = !!(selection && !selection.isCollapsed);
            if (choosing) return {id:'', link, selection:true};
            const hit = paintedRects.find(rect => x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom);
            return {id:hit ? hit.id : '', link, selection:false};
        }
    };
    Object.defineProperty(window, '__moonlight', {value:api, configurable:true});
})();
