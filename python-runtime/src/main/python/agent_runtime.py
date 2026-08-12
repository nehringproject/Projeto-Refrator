"""Trusted entry points for Refrator's embedded CPython runtime."""

from __future__ import annotations

import asyncio
import code
import contextlib
import hashlib
import importlib.metadata
import io
import json
import os
import runpy
import shutil
import sys
import traceback
import uuid
from collections import OrderedDict
from pathlib import Path
from typing import Any


_REPLS: dict[str, code.InteractiveConsole] = {}
_REPL_INTERRUPTED: set[str] = set()
_LITELLM_CANCELLED: set[str] = set()
_LITELLM_ROUTERS: OrderedDict[str, Any] = OrderedDict()
_MAX_LITELLM_ROUTERS = 8


def runtime_status() -> str:
    return json.dumps(
        {
            "ready": True,
            "python": sys.version,
            "executable": sys.executable,
            "platform": sys.platform,
            "prefix": sys.prefix,
            "litellm": _package_version("litellm"),
            "tokenizer_backend": "android_byte_fallback",
        },
        ensure_ascii=False,
    )


def execute(source: str, working_directory: str, overlay: str | None = None) -> str:
    return _capture(lambda: _execute_source(source, working_directory, overlay))


def run_file(path: str, working_directory: str, overlay: str | None = None) -> str:
    file_path = Path(path).resolve(strict=True)
    work = Path(working_directory).resolve(strict=True)
    _require_within(file_path, work)
    return _capture(lambda: _run_file(file_path, work, overlay))


def repl_open(working_directory: str, overlay: str | None = None) -> str:
    session_id = str(uuid.uuid4())
    namespace = {"__name__": "__console__", "__package__": None}
    console = code.InteractiveConsole(namespace)
    console.locals["__agent_working_directory__"] = working_directory
    console.locals["__agent_overlay__"] = overlay
    _REPLS[session_id] = console
    return session_id


def repl_write(
    session_id: str,
    source: str,
    working_directory: str,
    overlay: str | None = None,
) -> str:
    console = _REPLS.get(session_id)
    if console is None:
        raise ValueError("Unknown Python REPL session")
    work = Path(working_directory).resolve(strict=True)

    def push() -> bool:
        if session_id in _REPL_INTERRUPTED:
            _REPL_INTERRUPTED.discard(session_id)
            raise KeyboardInterrupt("Python REPL interrupted")
        with _execution_context(work, overlay):
            return console.push(source)

    return _capture(push)


def repl_interrupt(session_id: str) -> str:
    if session_id not in _REPLS:
        return json.dumps({"ok": False, "error": "Unknown Python REPL session"})
    _REPL_INTERRUPTED.add(session_id)
    return json.dumps({"ok": True, "interrupted": True, "session_id": session_id})


def repl_close(session_id: str) -> bool:
    _REPL_INTERRUPTED.discard(session_id)
    return _REPLS.pop(session_id, None) is not None


def package_install(requirement: str, overlay: str, cache_directory: str) -> str:
    requirement = requirement.strip()
    if not requirement or len(requirement) > 512 or "\x00" in requirement:
        raise ValueError("Invalid Python package requirement")
    target = Path(overlay).resolve()
    cache = Path(cache_directory).resolve()
    target.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)

    def install() -> dict[str, Any]:
        _patch_pip_asset_paths()
        from pip._internal.cli.main import main as pip_main

        result = pip_main(
            [
                "install",
                "--disable-pip-version-check",
                "--no-input",
                "--upgrade",
                "--target",
                str(target),
                "--cache-dir",
                str(cache),
                requirement,
            ]
        )
        if result != 0:
            raise RuntimeError(f"pip exited with status {result}")
        return {"requirement": requirement, "exit_code": result, "overlay": str(target)}

    return _capture(install)


def _patch_pip_asset_paths() -> None:
    """Make pip metadata scanning tolerate Chaquopy's read-only AssetPath objects.

    pip 25 assumes every importlib metadata path has pathlib's ``parent`` attribute. Chaquopy
    intentionally exposes APK assets through a path-like object which doesn't implement it. The
    metadata object itself is valid; only pip's optional installed-location annotation is missing.
    """
    from pip._internal.metadata.importlib import _envs
    from pip._internal.metadata.importlib._dists import Distribution

    finder = _envs._DistributionFinder
    if getattr(finder, "_agent_chaquopy_compatible", False):
        return

    def find(self: Any, location: str) -> Any:
        for distribution, info_location in self._find_impl(location):
            installed_location = getattr(info_location, "parent", None)
            yield Distribution(distribution, info_location, installed_location)

    finder.find = find
    finder._agent_chaquopy_compatible = True


def package_list(overlay: str) -> str:
    target = Path(overlay).resolve()
    target.mkdir(parents=True, exist_ok=True)
    packages = sorted(
        (
            {"name": item.metadata.get("Name", item.name), "version": item.version}
            for item in importlib.metadata.distributions(path=[str(target)])
        ),
        key=lambda item: item["name"].lower(),
    )
    return json.dumps({"ok": True, "packages": packages, "count": len(packages)})


def package_remove(distribution: str, overlay: str) -> str:
    requested = _normalized_distribution(distribution)
    target = Path(overlay).resolve()
    target.mkdir(parents=True, exist_ok=True)

    def remove() -> dict[str, Any]:
        installed = next(
            (
                item
                for item in importlib.metadata.distributions(path=[str(target)])
                if _normalized_distribution(item.metadata.get("Name", item.name)) == requested
            ),
            None,
        )
        if installed is None:
            raise ValueError("Python distribution is not installed in this workspace")
        removed = 0
        for relative in installed.files or ():
            candidate = Path(installed.locate_file(relative)).resolve()
            _require_within(candidate, target)
            if candidate.is_file() or candidate.is_symlink():
                candidate.unlink(missing_ok=True)
                removed += 1
        for child in sorted(target.rglob("*"), key=lambda item: len(item.parts), reverse=True):
            if child.is_dir():
                with contextlib.suppress(OSError):
                    child.rmdir()
        return {"distribution": distribution, "removed_files": removed}

    return _capture(remove)


def environment_status(overlay: str) -> str:
    target = Path(overlay).resolve()
    target.mkdir(parents=True, exist_ok=True)
    file_count = sum(1 for item in target.rglob("*") if item.is_file())
    total_bytes = sum(item.stat().st_size for item in target.rglob("*") if item.is_file())
    return json.dumps(
        {
            "ok": True,
            "overlay": str(target),
            "file_count": file_count,
            "bytes": total_bytes,
            "python": sys.version,
        },
        ensure_ascii=False,
    )


def environment_reset(overlay: str) -> str:
    target = Path(overlay).resolve()
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True, exist_ok=True)
    return json.dumps({"ok": True, "reset": True, "overlay": str(target)})


def runtime_test(working_directory: str, overlay: str | None = None) -> str:
    source = (
        "import json, platform, sys\n"
        "print(json.dumps({'answer': 6 * 7, 'python': sys.version_info[:3], "
        "'platform': platform.system()}))"
    )
    return execute(source, working_directory, overlay)


def litellm_status() -> str:
    try:
        import litellm

        return json.dumps(
            {"ready": True, "version": _package_version("litellm")},
            ensure_ascii=False,
        )
    except Exception as exc:  # pragma: no cover - returned to Android diagnostics
        return json.dumps(
            {"ready": False, "error": f"{type(exc).__name__}: {exc}"},
            ensure_ascii=False,
        )


def litellm_completion(request_json: str) -> str:
    """Call the real LiteLLM SDK without starting FastAPI or Prisma."""
    request = json.loads(request_json)
    model = request.pop("model")
    messages = request.pop("messages")
    deployments = request.pop("deployments", None)
    fallbacks = request.pop("fallbacks", None)

    async def invoke() -> Any:
        import litellm

        client = _litellm_client(litellm, deployments, fallbacks)
        response = await client.acompletion(model=model, messages=messages, **request)
        if hasattr(response, "model_dump"):
            return response.model_dump()
        if hasattr(response, "dict"):
            return response.dict()
        return response

    result = asyncio.run(invoke())
    return json.dumps(result, ensure_ascii=False, default=str)


def litellm_stream(request_json: str, callback: Any) -> None:
    """Stream normalized LiteLLM events to an in-process Binder callback."""
    request = json.loads(request_json)
    request_id = str(request.pop("request_id"))
    model = request.pop("model")
    messages = request.pop("messages")
    deployments = request.pop("deployments", None)
    fallbacks = request.pop("fallbacks", None)
    request["stream"] = True
    request.setdefault("stream_options", {"include_usage": True})

    async def invoke() -> None:
        import litellm

        tool_names_by_index: dict[int, str] = {}
        tool_arguments_by_index: dict[int, list[str]] = {}
        tool_ids_by_index: dict[int, str] = {}
        last_resolved_model = ""
        finish_reason: str | None = None
        client = _litellm_client(litellm, deployments, fallbacks)
        response = await client.acompletion(model=model, messages=messages, **request)
        async for chunk in response:
            if request_id in _LITELLM_CANCELLED:
                callback.onEvent(json.dumps({"type": "cancelled", "reason": "cancelled"}))
                return
            value = chunk.model_dump() if hasattr(chunk, "model_dump") else dict(chunk)
            resolved_model = str(value.get("model") or "")
            if resolved_model and resolved_model != last_resolved_model:
                last_resolved_model = resolved_model
                callback.onEvent(
                    json.dumps({"type": "model", "model_id": resolved_model}, ensure_ascii=False)
                )
            usage = value.get("usage")
            if usage:
                if hasattr(usage, "model_dump"):
                    usage = usage.model_dump()
                callback.onEvent(json.dumps({"type": "usage", "usage": usage}, default=str))
            for choice in value.get("choices") or ():
                if hasattr(choice, "model_dump"):
                    choice = choice.model_dump()
                delta = choice.get("delta") or {}
                if hasattr(delta, "model_dump"):
                    delta = delta.model_dump()
                reasoning = delta.get("reasoning_content") or delta.get("reasoning")
                if reasoning:
                    callback.onEvent(
                        json.dumps({"type": "reasoning_delta", "value": reasoning}, ensure_ascii=False)
                    )
                content = delta.get("content")
                if content:
                    callback.onEvent(
                        json.dumps({"type": "text_delta", "value": content}, ensure_ascii=False)
                    )
                for position, tool_call in enumerate(delta.get("tool_calls") or ()):
                    if hasattr(tool_call, "model_dump"):
                        tool_call = tool_call.model_dump()
                    try:
                        index = int(tool_call.get("index", position))
                    except (TypeError, ValueError):
                        index = position
                    supplied_id = tool_call.get("id")
                    if supplied_id:
                        tool_ids_by_index[index] = str(supplied_id)
                    function = tool_call.get("function") or {}
                    if hasattr(function, "model_dump"):
                        function = function.model_dump()
                    name = str(function.get("name") or "")
                    if name:
                        tool_names_by_index[index] = tool_names_by_index.get(index, "") + name
                    arguments = function.get("arguments")
                    if arguments:
                        tool_arguments_by_index.setdefault(index, []).append(str(arguments))
                choice_finish = choice.get("finish_reason")
                if choice_finish:
                    finish_reason = str(choice_finish)

        if finish_reason is None:
            raise RuntimeError("LiteLLM stream ended without a finish reason")

        # Tool fragments from different chunks are assembled by their provider index before any
        # event crosses Binder. This prevents a late call ID or parallel call from corrupting the
        # arguments of another tool.
        for index in sorted(set(tool_names_by_index) | set(tool_arguments_by_index)):
            call_id = tool_ids_by_index.get(index, f"tool-{index}")
            callback.onEvent(
                json.dumps(
                    {
                        "type": "tool_started",
                        "call_id": call_id,
                        "name": tool_names_by_index.get(index, ""),
                    },
                    ensure_ascii=False,
                )
            )
            callback.onEvent(
                json.dumps(
                    {
                        "type": "tool_arguments",
                        "call_id": call_id,
                        "value": "".join(tool_arguments_by_index.get(index, [])),
                    },
                    ensure_ascii=False,
                )
            )
            callback.onEvent(json.dumps({"type": "tool_completed", "call_id": call_id}))
        callback.onEvent(json.dumps({"type": "completed", "finish_reason": finish_reason}))

    try:
        asyncio.run(invoke())
        callback.onCompleted(json.dumps({"ok": True, "request_id": request_id}))
    except BaseException as exc:
        callback.onError(
            json.dumps(
                {
                    "ok": False,
                    "error": f"{type(exc).__name__}: {exc}",
                    "retryable": _retryable_provider_error(exc),
                },
                ensure_ascii=False,
            )
        )
    finally:
        _LITELLM_CANCELLED.discard(request_id)


def litellm_cancel(request_id: str) -> bool:
    _LITELLM_CANCELLED.add(request_id)
    return True


def _litellm_client(
    litellm: Any,
    deployments: list[dict[str, Any]] | None,
    fallbacks: list[dict[str, Any]] | None = None,
) -> Any:
    if not deployments:
        return litellm
    encoded = json.dumps(
        {"deployments": deployments, "fallbacks": fallbacks or []},
        sort_keys=True,
        separators=(",", ":"),
    )
    key = hashlib.sha256(encoded.encode("utf-8")).hexdigest()
    router = _LITELLM_ROUTERS.get(key)
    if router is None:
        router = litellm.Router(
            model_list=deployments,
            fallbacks=fallbacks or [],
            routing_strategy="simple-shuffle",
            num_retries=2,
            max_fallbacks=max(1, len(deployments) - 1),
            allowed_fails=1,
            cooldown_time=60,
            retry_after=0,
            enable_weighted_failover=True,
            enable_pre_call_checks=True,
        )
        _LITELLM_ROUTERS[key] = router
        while len(_LITELLM_ROUTERS) > _MAX_LITELLM_ROUTERS:
            _, stale = _LITELLM_ROUTERS.popitem(last=False)
            close = getattr(stale, "close", None)
            if callable(close):
                try:
                    close()
                except Exception:
                    pass
    else:
        _LITELLM_ROUTERS.move_to_end(key)
    return router


def _execute_source(source: str, working_directory: str, overlay: str | None) -> None:
    work = Path(working_directory).resolve(strict=True)
    with _execution_context(work, overlay):
        namespace = {"__name__": "__main__", "__package__": None}
        exec(compile(source, "<agent-python>", "exec"), namespace, namespace)


def _run_file(path: Path, work: Path, overlay: str | None) -> None:
    with _execution_context(work, overlay):
        runpy.run_path(str(path), run_name="__main__")


@contextlib.contextmanager
def _execution_context(work: Path, overlay: str | None):
    previous_cwd = Path.cwd()
    previous_path = list(sys.path)
    previous_modules = dict(sys.modules)
    transient_roots = [work]
    try:
        os.chdir(work)
        sys.path.insert(0, str(work))
        if overlay:
            overlay_path = Path(overlay).resolve()
            overlay_path.mkdir(parents=True, exist_ok=True)
            sys.path.insert(0, str(overlay_path))
            transient_roots.append(overlay_path)
        yield
    finally:
        os.chdir(previous_cwd)
        sys.path[:] = previous_path
        # CPython's module cache is process-global. Without this restoration, importing a package
        # from workspace A makes that exact module visible to workspace B even when B's overlay is
        # empty. Keep trusted base modules warm, but discard/restore anything loaded from a mutable
        # workspace or its dependency overlay.
        for name, module in list(sys.modules.items()):
            if _module_belongs_to(module, transient_roots):
                original = previous_modules.get(name)
                if original is None:
                    sys.modules.pop(name, None)
                else:
                    sys.modules[name] = original
        for root in transient_roots:
            sys.path_importer_cache.pop(str(root), None)


def _module_belongs_to(module: Any, roots: list[Path]) -> bool:
    location = getattr(module, "__file__", None)
    if not location:
        return False
    try:
        path = Path(str(location)).resolve()
    except (OSError, ValueError):
        return False
    for root in roots:
        try:
            path.relative_to(root)
            return True
        except ValueError:
            continue
    return False


def _capture(action) -> str:
    stdout = io.StringIO()
    stderr = io.StringIO()
    try:
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            value = action()
        return json.dumps(
            {
                "ok": True,
                "stdout": stdout.getvalue(),
                "stderr": stderr.getvalue(),
                "value": value,
            },
            ensure_ascii=False,
            default=str,
        )
    except BaseException as exc:
        return json.dumps(
            {
                "ok": False,
                "stdout": stdout.getvalue(),
                "stderr": stderr.getvalue(),
                "error": f"{type(exc).__name__}: {exc}",
                "traceback": traceback.format_exc(limit=20),
            },
            ensure_ascii=False,
        )


def _require_within(path: Path, root: Path) -> None:
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise ValueError("Python file is outside the workspace") from exc


def _package_version(name: str) -> str | None:
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return None


def _normalized_distribution(value: str) -> str:
    normalized = value.strip().lower().replace("_", "-").replace(".", "-")
    if not normalized or len(normalized) > 200:
        raise ValueError("Invalid Python distribution name")
    return normalized


def _retryable_provider_error(error: BaseException) -> bool:
    name = type(error).__name__.lower()
    message = str(error).lower()
    return any(
        marker in name or marker in message
        for marker in ("timeout", "connection", "ratelimit", "rate limit", "429", "500", "502", "503")
    )
