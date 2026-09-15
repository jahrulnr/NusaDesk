/*
 * Native Android/WebView selection support for xterm's real DOM renderer.
 *
 * There is deliberately no text mirror, canvas layer, clipboard API, or custom
 * action menu. Android selects the actual `.xterm-rows` text and owns Copy.
 * xterm normally replaces every row's children on render; that detaches the
 * browser selection. While a native selection is anchored in the rows, this
 * adapter postpones those row mutations and any requested fit. When selection
 * closes, the latest deferred row content and layout are applied.
 */
(function () {
  "use strict";

  function shouldFreezeRowMutation(hasDomSelection) {
    return !!hasDomSelection;
  }

  function shouldDeferFit(hasDomSelection) {
    return !!hasDomSelection;
  }

  function install(container) {
    if (!container) {
      return null;
    }
    var doc = container.ownerDocument;
    var rowsRoot = container.querySelector(".xterm-rows");
    if (!doc || !rowsRoot) {
      return null;
    }

    var patchedRows = [];
    var pendingFit = null;
    var disposed = false;

    function selectionInRows() {
      var selection = doc.getSelection ? doc.getSelection() : null;
      if (!selection || selection.isCollapsed) {
        return false;
      }
      return rowsRoot.contains(selection.anchorNode) ||
          rowsRoot.contains(selection.focusNode);
    }

    function patchRow(row) {
      if (!row || row.__linuxWrapperDomSelectionPatched ||
          typeof row.replaceChildren !== "function") {
        return;
      }
      var original = row.replaceChildren;
      var pendingArgs = null;
      row.__linuxWrapperDomSelectionPatched = true;
      row.__linuxWrapperFlushRow = function () {
        if (pendingArgs) {
          var args = pendingArgs;
          pendingArgs = null;
          original.apply(row, args);
        }
      };
      row.replaceChildren = function () {
        var args = Array.prototype.slice.call(arguments);
        if (shouldFreezeRowMutation(selectionInRows())) {
          pendingArgs = args;
          return;
        }
        original.apply(row, args);
      };
      patchedRows.push(row);
    }

    function patchCurrentRows() {
      for (var i = 0; i < rowsRoot.children.length; i++) {
        patchRow(rowsRoot.children[i]);
      }
    }

    function flush() {
      if (disposed || selectionInRows()) {
        return;
      }
      for (var i = 0; i < patchedRows.length; i++) {
        var row = patchedRows[i];
        if (row && typeof row.__linuxWrapperFlushRow === "function") {
          row.__linuxWrapperFlushRow();
        }
      }
      if (pendingFit) {
        var fit = pendingFit;
        pendingFit = null;
        fit();
      }
    }

    function requestFit(fit) {
      if (typeof fit !== "function") {
        return;
      }
      if (shouldDeferFit(selectionInRows())) {
        pendingFit = fit;
        return;
      }
      fit();
    }

    patchCurrentRows();
    var observer = new MutationObserver(patchCurrentRows);
    observer.observe(rowsRoot, { childList: true });
    doc.addEventListener("selectionchange", flush);

    return {
      requestFit: requestFit,
      hasSelection: selectionInRows,
      dispose: function () {
        disposed = true;
        observer.disconnect();
        doc.removeEventListener("selectionchange", flush);
        pendingFit = null;
      }
    };
  }

  var api = {
    shouldFreezeRowMutation: shouldFreezeRowMutation,
    shouldDeferFit: shouldDeferFit,
    install: install
  };
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
  if (typeof window !== "undefined") {
    window.LinuxWrapperDomSelection = api;
  }
})();
