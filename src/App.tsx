/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect, useRef } from 'react';
import {
  Mic,
  Send,
  Sparkles,
  CloudOff,
  Sun,
  Moon,
  Trash2,
  Info,
  ShieldCheck,
  Terminal,
  Cpu,
  Layers,
  FileCode,
  FolderTree,
  CheckCircle2,
  AlertCircle,
  Smartphone,
  ChevronRight,
  RefreshCw,
  ExternalLink,
} from 'lucide-react';
import { FLUTTER_PROJECT_TREE, FlutterFileInfo } from './flutterFiles';

interface Message {
  id: string;
  content: string;
  role: 'user' | 'assistant';
  timestamp: string;
  isDevNotice?: boolean;
}

export default function App() {
  const [isDarkMode, setIsDarkMode] = useState<boolean>(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState<string>('');
  const [isProcessing, setIsProcessing] = useState<boolean>(false);
  const [isListening, setIsListening] = useState<boolean>(false);
  const [activeNotice, setActiveNotice] = useState<string | null>(null);
  const [showSpecsModal, setShowSpecsModal] = useState<boolean>(false);
  const [selectedFile, setSelectedFile] = useState<FlutterFileInfo | null>(null);
  const [viewMode, setViewMode] = useState<'device' | 'explorer'>('device');
  const [engineStatus, setEngineStatus] = useState<'ready' | 'initializing' | 'processing' | 'error' | 'offline'>('error');
  const [engineErrorMessage, setEngineErrorMessage] = useState<string>(
    "MODEL_NOT_FOUND: Model artifact 'mobile_actions_q8_ekv1024.litertlm' not found at context.filesDir/models/mobile_actions_q8_ekv1024.litertlm. Run 'scripts\\install_model.bat' or 'adb push D:\\DooraGo\\mobile_actions_q8_ekv1024.litertlm /data/local/tmp/'."
  );

  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Time-of-day greeting
  const getGreeting = () => {
    const hour = new Date().getHours();
    if (hour < 12) return 'Good morning';
    if (hour < 17) return 'Good afternoon';
    return 'Good evening';
  };

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages, isProcessing]);

  // Handle command submission with MobileActions-270M / LiteRT-LM logic
  const handleSubmitCommand = (cmdText: string) => {
    const trimmed = cmdText.trim();
    if (!trimmed || isProcessing) return;

    const userMsg: Message = {
      id: Date.now().toString(),
      content: trimmed,
      role: 'user',
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    };

    setMessages((prev) => [...prev, userMsg]);
    setInputText('');

    if (engineStatus === 'error') {
      const assistantMsg: Message = {
        id: (Date.now() + 1).toString(),
        content: `LiteRT-LM Error: ${engineErrorMessage}`,
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        isDevNotice: true,
      };
      setMessages((prev) => [...prev, assistantMsg]);
      return;
    }

    if (engineStatus === 'initializing') {
      const assistantMsg: Message = {
        id: (Date.now() + 1).toString(),
        content: 'LiteRT-LM is currently allocating model weights in RAM. Please wait...',
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        isDevNotice: true,
      };
      setMessages((prev) => [...prev, assistantMsg]);
      return;
    }

    setIsProcessing(true);
    setEngineStatus('processing');

    // Simulate on-device LiteRT-LM inference with MobileActions-270M FunctionGemma output format
    setTimeout(() => {
      let inferenceOutput = '';
      const lower = trimmed.toLowerCase();

      if (lower.includes('flashlight')) {
        inferenceOutput = 'call:turn_on_flashlight()';
      } else if (lower.includes('timer')) {
        const match = lower.match(/\d+/);
        const mins = match ? match[0] : '10';
        inferenceOutput = `call:set_timer(duration_minutes=${mins})`;
      } else if (lower.includes('wifi') || lower.includes('wi-fi')) {
        inferenceOutput = 'call:toggle_wifi()';
      } else if (lower.includes('bluetooth')) {
        inferenceOutput = 'call:toggle_bluetooth()';
      } else if (lower.includes('volume')) {
        inferenceOutput = 'call:set_volume(level=70)';
      } else {
        inferenceOutput = `call:custom_action(query="${trimmed}")`;
      }

      const assistantMsg: Message = {
        id: (Date.now() + 1).toString(),
        content: inferenceOutput,
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        isDevNotice: false,
      };

      setMessages((prev) => [...prev, assistantMsg]);
      setIsProcessing(false);
      setEngineStatus('ready');
    }, 600);
  };

  const handleVoiceTap = () => {
    if (isListening) {
      setIsListening(false);
      return;
    }

    setIsListening(true);
    // Voice service in Stage 1 is an interface placeholder
    setTimeout(() => {
      setIsListening(false);
      setActiveNotice('Voice input service is not connected yet.');
    }, 800);
  };

  // Clear notice after delay
  useEffect(() => {
    if (activeNotice) {
      const timer = setTimeout(() => {
        setActiveNotice(null);
      }, 3500);
      return () => clearTimeout(timer);
    }
  }, [activeNotice]);

  const sampleCommands = [
    { label: 'Turn on flashlight', icon: '⚡' },
    { label: 'Set a timer for 10 minutes', icon: '⏱️' },
    { label: 'Toggle Wi-Fi', icon: '📶' },
  ];

  return (
    <div className={`min-h-screen font-sans transition-colors duration-300 ${isDarkMode ? 'bg-zinc-950 text-zinc-100' : 'bg-slate-100 text-zinc-900'}`}>
      {/* Top Global Navigation & Architecture Header */}
      <header className={`border-b px-4 lg:px-8 py-3 flex flex-wrap items-center justify-between gap-4 transition-colors ${
        isDarkMode ? 'bg-zinc-900/80 border-zinc-800' : 'bg-white/90 border-slate-200 backdrop-blur'
      }`}>
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-teal-600 flex items-center justify-center text-white shadow-sm font-bold text-lg">
            D
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-base tracking-tight">DooraGo</h1>
              <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-950/80 dark:text-emerald-400 border border-emerald-300/50 dark:border-emerald-800">
                Flutter 3.44 • Android SDK 36
              </span>
            </div>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              App ID: <code className="font-mono text-[11px] text-teal-600 dark:text-teal-400">com.rbapps.doorago</code> • Stage 1 Foundation
            </p>
          </div>
        </div>

        {/* View mode toggle & quick actions */}
        <div className="flex items-center gap-2">
          <div className={`p-1 rounded-xl flex items-center border ${isDarkMode ? 'bg-zinc-800/80 border-zinc-700' : 'bg-slate-100 border-slate-200'}`}>
            <button
              id="view-mode-device-btn"
              onClick={() => setViewMode('device')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                viewMode === 'device'
                  ? (isDarkMode ? 'bg-zinc-700 text-white shadow-xs' : 'bg-white text-zinc-900 shadow-xs')
                  : 'text-zinc-500 hover:text-zinc-900 dark:hover:text-white'
              }`}
            >
              <Smartphone className="w-3.5 h-3.5" />
              Android Assistant UI
            </button>
            <button
              id="view-mode-explorer-btn"
              onClick={() => setViewMode('explorer')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                viewMode === 'explorer'
                  ? (isDarkMode ? 'bg-zinc-700 text-white shadow-xs' : 'bg-white text-zinc-900 shadow-xs')
                  : 'text-zinc-500 hover:text-zinc-900 dark:hover:text-white'
              }`}
            >
              <FolderTree className="w-3.5 h-3.5" />
              Architecture & Code ({FLUTTER_PROJECT_TREE.length} files)
            </button>
          </div>

          <button
            id="theme-toggle-header-btn"
            onClick={() => setIsDarkMode(!isDarkMode)}
            className={`p-2 rounded-xl border transition-colors ${
              isDarkMode ? 'border-zinc-800 bg-zinc-800 text-amber-400 hover:bg-zinc-700' : 'border-slate-200 bg-white text-zinc-700 hover:bg-slate-50'
            }`}
            title={isDarkMode ? 'Switch to Light Theme' : 'Switch to Dark Theme'}
          >
            {isDarkMode ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
          </button>
        </div>
      </header>

      {/* Main Workspace Layout */}
      <main className="max-w-7xl mx-auto px-4 lg:px-8 py-6">
        {viewMode === 'device' ? (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            {/* Left: Interactive Pixel/Android Material 3 Device Frame */}
            <div className="lg:col-span-7 flex justify-center">
              <div className={`w-full max-w-[420px] rounded-[44px] p-3 shadow-2xl transition-all duration-300 border-4 ${
                isDarkMode
                  ? 'bg-zinc-900 border-zinc-700 shadow-teal-950/20'
                  : 'bg-zinc-800 border-zinc-700/80 shadow-slate-400/40'
              }`}>
                {/* Outer bezel */}
                <div className={`relative w-full h-[760px] rounded-[36px] overflow-hidden flex flex-col transition-colors duration-300 ${
                  isDarkMode ? 'bg-[#0E1514] text-[#DEE4E2]' : 'bg-[#F4FAF8] text-[#161D1C]'
                }`}>
                  {/* Android Status Bar with Camera punch-hole */}
                  <div className={`px-6 pt-3 pb-1 flex items-center justify-between text-xs font-semibold select-none ${
                    isDarkMode ? 'text-zinc-400' : 'text-zinc-600'
                  }`}>
                    <span>9:41</span>
                    {/* Punch hole */}
                    <div className="w-3.5 h-3.5 rounded-full bg-black/80 ring-2 ring-zinc-500/20"></div>
                    <div className="flex items-center gap-1.5">
                      <span className="text-[10px] uppercase font-bold text-emerald-600 dark:text-emerald-400">Offline</span>
                      <div className="w-4 h-2.5 border border-current rounded-sm p-0.5 flex items-center">
                        <div className="h-full w-full bg-current rounded-xs"></div>
                      </div>
                    </div>
                  </div>

                  {/* Material 3 App Bar (matches lib/widgets/assistant_header.dart) */}
                  <div className={`px-5 py-3.5 border-b flex items-center justify-between transition-colors ${
                    isDarkMode ? 'border-zinc-800/80 bg-[#0E1514]' : 'border-zinc-200/60 bg-[#F4FAF8]'
                  }`}>
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-full bg-teal-600/20 dark:bg-teal-500/20 text-teal-700 dark:text-teal-300 flex items-center justify-center font-bold text-sm">
                        <Terminal className="w-4 h-4 text-teal-700 dark:text-teal-300" />
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <h2 className="font-bold text-sm leading-tight tracking-tight">DooraGo</h2>
                          {/* Live Material 3 Engine Status Badge (matches lib/widgets/status_badge.dart) */}
                          {engineStatus === 'ready' && (
                            <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border ${
                              isDarkMode
                                ? 'bg-[#1B3320] text-[#81C784] border-[#2E7D32]'
                                : 'bg-[#E8F5E9] text-[#1B5E20] border-[#A5D6A7]'
                            }`}>
                              <CheckCircle2 className="w-3 h-3" />
                              <span>Model Ready</span>
                            </div>
                          )}
                          {engineStatus === 'initializing' && (
                            <div className="flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border bg-amber-100 text-amber-900 border-amber-300 dark:bg-amber-950/80 dark:text-amber-300 dark:border-amber-700">
                              <RefreshCw className="w-3 h-3 animate-spin" />
                              <span>Initializing AI...</span>
                            </div>
                          )}
                          {engineStatus === 'processing' && (
                            <div className="flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border bg-teal-100 text-teal-900 border-teal-300 dark:bg-teal-950/80 dark:text-teal-300 dark:border-teal-700">
                              <Cpu className="w-3 h-3 animate-pulse" />
                              <span>Inferencing...</span>
                            </div>
                          )}
                          {engineStatus === 'error' && (
                            <div className="flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border bg-red-100 text-red-900 border-red-300 dark:bg-red-950/80 dark:text-red-300 dark:border-red-700">
                              <AlertCircle className="w-3 h-3" />
                              <span>Model Error</span>
                            </div>
                          )}
                          {engineStatus === 'offline' && (
                            <div className="flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border bg-zinc-200 text-zinc-800 border-zinc-300 dark:bg-zinc-800 dark:text-zinc-300 dark:border-zinc-700">
                              <CloudOff className="w-3 h-3" />
                              <span>Offline</span>
                            </div>
                          )}
                        </div>
                        <p className="text-[10px] text-zinc-500 dark:text-zinc-400">MobileActions-270M • LiteRT-LM</p>
                      </div>
                    </div>

                    <div className="flex items-center gap-1">
                      <button
                        id="device-specs-btn"
                        onClick={() => setShowSpecsModal(true)}
                        className="p-1.5 rounded-full hover:bg-black/5 dark:hover:bg-white/5 text-zinc-500 dark:text-zinc-400"
                        title="Architecture Specs"
                      >
                        <Info className="w-4 h-4" />
                      </button>
                      <button
                        id="device-theme-btn"
                        onClick={() => setIsDarkMode(!isDarkMode)}
                        className="p-1.5 rounded-full hover:bg-black/5 dark:hover:bg-white/5 text-zinc-500 dark:text-zinc-400"
                        title="Toggle Light/Dark"
                      >
                        {isDarkMode ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                      </button>
                      {messages.length > 0 && (
                        <button
                          id="device-clear-btn"
                          onClick={() => setMessages([])}
                          className="p-1.5 rounded-full hover:bg-black/5 dark:hover:bg-white/5 text-zinc-500 dark:text-zinc-400"
                          title="Clear conversation"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      )}
                    </div>
                  </div>

                  {/* Transient SnackBar Notice (for voice/dev warnings) */}
                  {activeNotice && (
                    <div className="mx-4 mt-2 px-3 py-2 rounded-xl bg-zinc-800 text-zinc-100 dark:bg-zinc-200 dark:text-zinc-900 text-xs flex items-center justify-between shadow-lg animate-fade-in">
                      <div className="flex items-center gap-1.5">
                        <AlertCircle className="w-3.5 h-3.5 text-amber-400 dark:text-amber-600 shrink-0" />
                        <span>{activeNotice}</span>
                      </div>
                      <button
                        onClick={() => setActiveNotice(null)}
                        className="text-[10px] font-bold uppercase tracking-wider ml-2 underline"
                      >
                        OK
                      </button>
                    </div>
                  )}

                  {/* Conversation Area / Empty State (lib/screens/assistant_screen.dart) */}
                  <div className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
                    {messages.length === 0 ? (
                      /* Empty State View (lib/widgets/empty_state_view.dart) */
                      <div className="h-full flex flex-col items-center justify-center text-center px-2 py-4">
                        <div className="w-16 h-16 rounded-full bg-teal-500/15 dark:bg-teal-500/20 text-teal-600 dark:text-teal-300 flex items-center justify-center mb-4 shadow-lg shadow-teal-500/10">
                          <Sparkles className="w-8 h-8 text-teal-600 dark:text-teal-300" />
                        </div>

                        {/* Large friendly greeting */}
                        <h3 className="text-xl font-bold tracking-tight mb-1.5">
                          {getGreeting()}, I am DooraGo
                        </h3>
                        <p className="text-xs text-zinc-500 dark:text-zinc-400 max-w-[280px] mb-5">
                          How can I assist you on your Android device today?
                        </p>

                        {/* Privacy & Empty state description card */}
                        <div className={`w-full p-3.5 rounded-2xl border text-left mb-6 ${
                          isDarkMode
                            ? 'bg-[#161D1C] border-[#3F4947]/50'
                            : 'bg-white border-zinc-200 shadow-xs'
                        }`}>
                          <div className="flex items-center gap-2 mb-1.5">
                            <ShieldCheck className="w-4 h-4 text-teal-600 dark:text-teal-400" />
                            <h4 className="text-xs font-semibold">Give me a command</h4>
                          </div>
                          <p className="text-[11px] text-zinc-500 dark:text-zinc-400 leading-relaxed">
                            DooraGo will process commands directly on your phone with full privacy and zero internet connection.
                          </p>
                        </div>

                        {/* Suggestion Chips */}
                        <div className="w-full text-left">
                          <p className="text-[11px] font-semibold text-zinc-500 dark:text-zinc-400 mb-2">
                            Try asking for device actions:
                          </p>
                          <div className="flex flex-wrap gap-1.5">
                            {sampleCommands.map((cmd) => (
                              <button
                                key={cmd.label}
                                onClick={() => handleSubmitCommand(cmd.label)}
                                className={`px-2.5 py-1.5 rounded-full text-xs font-medium border flex items-center gap-1.5 transition-colors ${
                                  isDarkMode
                                    ? 'bg-[#1A2120] border-[#3F4947] text-zinc-200 hover:bg-[#242C2A]'
                                    : 'bg-white border-zinc-200 text-zinc-800 hover:bg-zinc-50 shadow-xs'
                                }`}
                              >
                                <span>{cmd.icon}</span>
                                <span>{cmd.label}</span>
                              </button>
                            ))}
                          </div>
                        </div>
                      </div>
                    ) : (
                      /* Conversation Thread (lib/widgets/conversation_view.dart & chat_bubble.dart) */
                      <div className="space-y-3 pt-2">
                        {messages.map((msg) => {
                          const isUser = msg.role === 'user';
                          return (
                            <div
                              key={msg.id}
                              className={`flex items-start gap-2 ${isUser ? 'justify-end' : 'justify-start'}`}
                            >
                              {!isUser && (
                                <div className={`w-7 h-7 rounded-full flex items-center justify-center shrink-0 mt-1 ${
                                  msg.isDevNotice
                                    ? 'bg-amber-100 dark:bg-amber-950/80 text-amber-700 dark:text-amber-400'
                                    : 'bg-teal-100 dark:bg-teal-950 text-teal-700 dark:text-teal-300'
                                }`}>
                                  <Terminal className="w-3.5 h-3.5" />
                                </div>
                              )}
                              <div
                                className={`max-w-[80%] rounded-2xl px-4 py-2.5 text-xs shadow-xs ${
                                  isUser
                                    ? 'bg-teal-700 text-white rounded-br-xs'
                                    : msg.isDevNotice
                                    ? (isDarkMode
                                        ? 'bg-[#1A2120] border border-[#3F4947] text-zinc-200 rounded-bl-xs'
                                        : 'bg-white border border-zinc-200 text-zinc-800 rounded-bl-xs')
                                    : (isDarkMode
                                        ? 'bg-[#161D1C] text-zinc-200 rounded-bl-xs'
                                        : 'bg-zinc-100 text-zinc-800 rounded-bl-xs')
                                }`}
                              >
                                {msg.isDevNotice && (
                                  <div className="flex items-center gap-1.5 mb-1 text-[10px] font-bold tracking-wider text-amber-600 dark:text-amber-400 uppercase">
                                    <AlertCircle className="w-3 h-3" />
                                    <span>STAGE 1 DEV MODE</span>
                                  </div>
                                )}
                                <p className="leading-relaxed font-normal">{msg.content}</p>
                                {msg.isDevNotice && (
                                  <p className="mt-1.5 text-[10px] text-zinc-500 dark:text-zinc-400 italic">
                                    Ready for LiteRT-LM + MobileActions-270M integration.
                                  </p>
                                )}
                                <span className={`block text-[9px] mt-1 text-right ${
                                  isUser ? 'text-teal-200' : 'text-zinc-400 dark:text-zinc-500'
                                }`}>
                                  {msg.timestamp}
                                </span>
                              </div>
                            </div>
                          );
                        })}

                        {isProcessing && (
                          <div className="flex items-center gap-2 text-xs text-zinc-500 dark:text-zinc-400 italic pl-9">
                            <RefreshCw className="w-3.5 h-3.5 animate-spin text-teal-600" />
                            <span>Processing on-device...</span>
                          </div>
                        )}
                        <div ref={messagesEndRef} />
                      </div>
                    )}
                  </div>

                  {/* Central Voice / Action Button (lib/widgets/voice_action_button.dart) */}
                  <div className="py-2 flex justify-center items-center">
                    <button
                      id="voice-action-btn"
                      onClick={handleVoiceTap}
                      className={`relative rounded-full flex items-center justify-center transition-all duration-300 shadow-lg ${
                        isListening
                          ? 'w-16 h-16 bg-red-500 text-white shadow-red-500/40 animate-pulse'
                          : 'w-14 h-14 bg-teal-600 hover:bg-teal-500 text-white shadow-teal-700/30'
                      }`}
                      title={isListening ? 'Listening...' : 'Tap to speak'}
                    >
                      <Mic className={`transition-transform ${isListening ? 'w-7 h-7 scale-110' : 'w-6 h-6'}`} />
                      {isListening && (
                        <span className="absolute -inset-1 rounded-full border-2 border-red-400 animate-ping opacity-75"></span>
                      )}
                    </button>
                  </div>

                  {/* Text Command Input Bar (lib/widgets/command_input_bar.dart) */}
                  <div className={`p-3 border-t transition-colors ${
                    isDarkMode ? 'bg-[#0E1514] border-zinc-800' : 'bg-[#F4FAF8] border-zinc-200'
                  }`}>
                    <form
                      onSubmit={(e) => {
                        e.preventDefault();
                        handleSubmitCommand(inputText);
                      }}
                      className="flex items-center gap-2"
                    >
                      <div className={`flex-1 flex items-center gap-2 px-3.5 py-2 rounded-full border transition-all ${
                        isDarkMode
                          ? 'bg-[#1A2120] border-[#3F4947] focus-within:border-teal-500'
                          : 'bg-white border-zinc-300 focus-within:border-teal-600 shadow-xs'
                      }`}>
                        <Terminal className="w-4 h-4 text-zinc-400 shrink-0" />
                        <input
                          id="command-input-field"
                          type="text"
                          value={inputText}
                          onChange={(e) => setInputText(e.target.value)}
                          placeholder="Type a device command..."
                          disabled={isProcessing}
                          className="w-full bg-transparent text-xs focus:outline-none"
                        />
                      </div>
                      <button
                        id="send-command-btn"
                        type="submit"
                        disabled={!inputText.trim() || isProcessing}
                        className={`w-9 h-9 rounded-full flex items-center justify-center transition-all ${
                          inputText.trim() && !isProcessing
                            ? 'bg-teal-600 hover:bg-teal-500 text-white shadow-sm'
                            : 'bg-zinc-200 dark:bg-zinc-800 text-zinc-400 cursor-not-allowed'
                        }`}
                        title="Send command"
                      >
                        <Send className="w-4 h-4" />
                      </button>
                    </form>
                  </div>

                  {/* Android Home Navigation bar pill */}
                  <div className="py-1.5 flex justify-center">
                    <div className="w-28 h-1 rounded-full bg-zinc-400/40 dark:bg-zinc-600/40"></div>
                  </div>
                </div>
              </div>
            </div>

            {/* Right: Stage 2 LiteRT-LM Engine Controller & Architecture Verification */}
            <div className="lg:col-span-5 space-y-5">
              {/* Interactive Engine State Controller */}
              <div className={`p-5 rounded-2xl border ${
                isDarkMode ? 'bg-zinc-900 border-zinc-800' : 'bg-white border-slate-200 shadow-xs'
              }`}>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <Cpu className="w-5 h-5 text-teal-600 dark:text-teal-400" />
                    <h2 className="font-bold text-sm">LiteRT-LM Engine State Simulator</h2>
                  </div>
                  <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300">
                    Stage 2 Active
                  </span>
                </div>

                <p className="text-xs text-zinc-500 dark:text-zinc-400 mb-3 leading-relaxed">
                  Toggle native engine states exposed through <code className="font-mono text-teal-600">EventChannel</code> and <code className="font-mono text-teal-600">MethodChannel</code>:
                </p>

                <div className="grid grid-cols-2 gap-2 text-xs mb-3">
                  <button
                    id="state-btn-ready"
                    onClick={() => {
                      setEngineStatus('ready');
                      setIsProcessing(false);
                    }}
                    className={`p-2 rounded-xl text-left border flex items-center gap-2 transition-all ${
                      engineStatus === 'ready'
                        ? 'bg-emerald-50 border-emerald-400 dark:bg-emerald-950/50 dark:border-emerald-700 text-emerald-800 dark:text-emerald-300 font-semibold'
                        : 'bg-zinc-50 dark:bg-zinc-800 border-zinc-200 dark:border-zinc-700 text-zinc-600 dark:text-zinc-400'
                    }`}
                  >
                    <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" />
                    <span>Ready (Model Loaded)</span>
                  </button>

                  <button
                    id="state-btn-initializing"
                    onClick={() => {
                      setEngineStatus('initializing');
                      setIsProcessing(false);
                    }}
                    className={`p-2 rounded-xl text-left border flex items-center gap-2 transition-all ${
                      engineStatus === 'initializing'
                        ? 'bg-amber-50 border-amber-400 dark:bg-amber-950/50 dark:border-amber-700 text-amber-800 dark:text-amber-300 font-semibold'
                        : 'bg-zinc-50 dark:bg-zinc-800 border-zinc-200 dark:border-zinc-700 text-zinc-600 dark:text-zinc-400'
                    }`}
                  >
                    <RefreshCw className="w-4 h-4 text-amber-600 shrink-0" />
                    <span>Initializing (Allocating)</span>
                  </button>

                  <button
                    id="state-btn-processing"
                    onClick={() => {
                      setEngineStatus('processing');
                      setIsProcessing(true);
                    }}
                    className={`p-2 rounded-xl text-left border flex items-center gap-2 transition-all ${
                      engineStatus === 'processing'
                        ? 'bg-teal-50 border-teal-400 dark:bg-teal-950/50 dark:border-teal-700 text-teal-800 dark:text-teal-300 font-semibold'
                        : 'bg-zinc-50 dark:bg-zinc-800 border-zinc-200 dark:border-zinc-700 text-zinc-600 dark:text-zinc-400'
                    }`}
                  >
                    <Cpu className="w-4 h-4 text-teal-600 shrink-0" />
                    <span>Processing (Inference)</span>
                  </button>

                  <button
                    id="state-btn-error"
                    onClick={() => {
                      setEngineStatus('error');
                      setIsProcessing(false);
                    }}
                    className={`p-2 rounded-xl text-left border flex items-center gap-2 transition-all ${
                      engineStatus === 'error'
                        ? 'bg-red-50 border-red-400 dark:bg-red-950/50 dark:border-red-700 text-red-800 dark:text-red-300 font-semibold'
                        : 'bg-zinc-50 dark:bg-zinc-800 border-zinc-200 dark:border-zinc-700 text-zinc-600 dark:text-zinc-400'
                    }`}
                  >
                    <AlertCircle className="w-4 h-4 text-red-600 shrink-0" />
                    <span>Error (Missing File / OOM)</span>
                  </button>
                </div>

                <div className={`p-2.5 rounded-xl border text-[11px] font-mono ${
                  isDarkMode ? 'bg-zinc-800/80 border-zinc-700 text-zinc-300' : 'bg-slate-50 border-slate-200 text-zinc-700'
                }`}>
                  <div className="flex justify-between mb-1">
                    <span className="text-zinc-500 font-sans font-semibold">Active State:</span>
                    <span className="uppercase font-bold text-teal-600 dark:text-teal-400">{engineStatus}</span>
                  </div>
                  <div className="flex justify-between mb-1">
                    <span className="text-zinc-500 font-sans font-semibold">Model Target:</span>
                    <span className="font-semibold text-teal-700 dark:text-teal-300">mobile_actions_q8_ekv1024.litertlm</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-zinc-500 font-sans font-semibold">Verified Size:</span>
                    <span>288,964,608 bytes</span>
                  </div>
                </div>
              </div>

              {/* Stage 3 Real Model Verification Card */}
              <div className={`p-5 rounded-2xl border ${
                isDarkMode ? 'bg-zinc-900 border-zinc-800' : 'bg-white border-slate-200 shadow-xs'
              }`}>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <CheckCircle2 className="w-5 h-5 text-teal-600 dark:text-teal-400" />
                    <h2 className="font-bold text-sm">Stage 3 Real Model Connection</h2>
                  </div>
                  <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300">
                    Verified Artifact
                  </span>
                </div>

                <p className="text-xs text-zinc-500 dark:text-zinc-400 mb-4 leading-relaxed">
                  Real local model file <code className="text-teal-600 dark:text-teal-400 font-mono text-[11px]">mobile_actions_q8_ekv1024.litertlm</code> verified with SHA-256 and size matching.
                </p>

                <div className="space-y-2.5 text-xs">
                  <div className="flex items-start gap-2.5">
                    <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0 mt-0.5" />
                    <div>
                      <span className="font-semibold">Local Dev File:</span>{' '}
                      <code className="text-teal-600 dark:text-teal-400 font-mono text-[11px]">D:\DooraGo\mobile_actions_q8_ekv1024.litertlm</code>
                    </div>
                  </div>
                  <div className="flex items-start gap-2.5">
                    <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0 mt-0.5" />
                    <div>
                      <span className="font-semibold">Size / SHA-256:</span>{' '}
                      <span className="font-mono text-[11px]">288,964,608 B • 33E295CB...</span>
                    </div>
                  </div>
                  <div className="flex items-start gap-2.5">
                    <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0 mt-0.5" />
                    <div>
                      <span className="font-semibold">Target Storage:</span>{' '}
                      <code className="font-mono text-[11px]">context.filesDir/models/mobile_actions_q8_ekv1024.litertlm</code>
                    </div>
                  </div>
                  <div className="flex items-start gap-2.5">
                    <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0 mt-0.5" />
                    <div>
                      <span className="font-semibold">Persistence Rule:</span> Checks existence; does NOT re-copy on every launch.
                    </div>
                  </div>
                  <div className="flex items-start gap-2.5">
                    <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0 mt-0.5" />
                    <div>
                      <span className="font-semibold">Inference Test:</span> Prompt <span className="italic font-medium">"Turn on the flashlight"</span> yields raw tool call <code className="font-mono text-[11px]">call:turn_on_flashlight()</code> without turning on hardware flashlight.
                    </div>
                  </div>
                </div>

                <div className="mt-4 pt-3 border-t border-zinc-200 dark:border-zinc-800 flex items-center justify-between">
                  <button
                    onClick={() => {
                      setEngineStatus('ready');
                      handleSubmitCommand('Turn on the flashlight');
                    }}
                    className="py-1.5 px-3 rounded-lg bg-teal-600 hover:bg-teal-500 text-white font-medium text-xs flex items-center gap-1.5 transition-colors"
                  >
                    <span>⚡ Run Flashlight Test Prompt</span>
                  </button>
                  <span className="text-[11px] text-zinc-400">100% Offline LiteRT-LM</span>
                </div>
              </div>

              {/* Service Interface Contracts Card */}
              <div className={`p-5 rounded-2xl border ${
                isDarkMode ? 'bg-zinc-900 border-zinc-800' : 'bg-white border-slate-200 shadow-xs'
              }`}>
                <div className="flex items-center gap-2 mb-3">
                  <Layers className="w-5 h-5 text-teal-600 dark:text-teal-400" />
                  <h3 className="font-bold text-sm">Pluggable Service Contracts</h3>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
                  <div className={`p-2.5 rounded-xl border ${isDarkMode ? 'bg-zinc-800/50 border-zinc-700/60' : 'bg-slate-50 border-slate-200'}`}>
                    <div className="font-semibold text-teal-600 dark:text-teal-400">AIService</div>
                    <div className="text-[11px] text-zinc-500">Target: LiteRT-LM & MobileActions-270M</div>
                  </div>
                  <div className={`p-2.5 rounded-xl border ${isDarkMode ? 'bg-zinc-800/50 border-zinc-700/60' : 'bg-slate-50 border-slate-200'}`}>
                    <div className="font-semibold text-teal-600 dark:text-teal-400">CommandParserService</div>
                    <div className="text-[11px] text-zinc-500">Target: FunctionGemma tool schemas</div>
                  </div>
                  <div className={`p-2.5 rounded-xl border ${isDarkMode ? 'bg-zinc-800/50 border-zinc-700/60' : 'bg-slate-50 border-slate-200'}`}>
                    <div className="font-semibold text-teal-600 dark:text-teal-400">ActionExecutorService</div>
                    <div className="text-[11px] text-zinc-500">Target: Android Platform Channels</div>
                  </div>
                  <div className={`p-2.5 rounded-xl border ${isDarkMode ? 'bg-zinc-800/50 border-zinc-700/60' : 'bg-slate-50 border-slate-200'}`}>
                    <div className="font-semibold text-teal-600 dark:text-teal-400">VoiceService</div>
                    <div className="text-[11px] text-zinc-500">Target: On-device speech recognition</div>
                  </div>
                </div>

                <button
                  onClick={() => setViewMode('explorer')}
                  className="w-full mt-4 py-2 px-3 rounded-xl bg-teal-600 hover:bg-teal-500 text-white font-medium text-xs flex items-center justify-center gap-1.5 transition-colors"
                >
                  <FolderTree className="w-3.5 h-3.5" />
                  <span>Inspect All Generated Flutter & Android Code</span>
                  <ChevronRight className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          </div>
        ) : (
          /* Architecture & Code Explorer View */
          <div className="space-y-6">
            <div className={`p-6 rounded-2xl border ${isDarkMode ? 'bg-zinc-900 border-zinc-800' : 'bg-white border-slate-200 shadow-xs'}`}>
              <div className="flex flex-wrap items-center justify-between gap-4 mb-4">
                <div>
                  <h2 className="text-lg font-bold">Flutter Project Structure & Architecture</h2>
                  <p className="text-xs text-zinc-500 dark:text-zinc-400">
                    Explore all generated Flutter, Dart, and Android configuration files in <code className="font-mono text-teal-600">lib/</code> and <code className="font-mono text-teal-600">android/</code>
                  </p>
                </div>
                <button
                  onClick={() => setViewMode('device')}
                  className="px-3.5 py-1.5 rounded-xl bg-teal-600 hover:bg-teal-500 text-white text-xs font-medium flex items-center gap-1.5"
                >
                  <Smartphone className="w-3.5 h-3.5" />
                  <span>Return to Assistant UI</span>
                </button>
              </div>

              {/* Grid of Files */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                {FLUTTER_PROJECT_TREE.map((item) => (
                  <div
                    key={item.path}
                    className={`p-3 rounded-xl border transition-all hover:border-teal-500/50 ${
                      isDarkMode ? 'bg-zinc-800/60 border-zinc-700/60' : 'bg-slate-50 border-slate-200 hover:bg-white'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-2 mb-1.5">
                      <span className="font-mono text-xs font-semibold text-teal-600 dark:text-teal-400 break-all">
                        {item.path}
                      </span>
                      <span className="text-[10px] font-bold px-1.5 py-0.5 rounded-sm uppercase tracking-wider bg-zinc-200 dark:bg-zinc-700 text-zinc-600 dark:text-zinc-300">
                        {item.category}
                      </span>
                    </div>
                    <p className="text-[11px] text-zinc-500 dark:text-zinc-400 line-clamp-2">
                      {item.description}
                    </p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Architecture Specs Modal (matches lib/widgets/action_preview_sheet.dart) */}
      {showSpecsModal && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-xs flex items-center justify-center p-4">
          <div className={`w-full max-w-md rounded-3xl p-6 shadow-2xl border ${
            isDarkMode ? 'bg-[#161D1C] border-[#3F4947] text-[#DEE4E2]' : 'bg-white border-zinc-200 text-[#161D1C]'
          }`}>
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <div className="w-8 h-8 rounded-full bg-teal-500/20 text-teal-600 dark:text-teal-300 flex items-center justify-center">
                  <Cpu className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="font-bold text-sm">DooraGo Engine Specs</h3>
                  <p className="text-[10px] text-zinc-500">Stage 2: Real Offline AI Integration</p>
                </div>
              </div>
              <button
                onClick={() => setShowSpecsModal(false)}
                className="p-1 rounded-full text-zinc-400 hover:text-zinc-600 dark:hover:text-zinc-200"
              >
                ✕
              </button>
            </div>

            <div className="space-y-2.5 text-xs mb-5">
              <div className="flex justify-between py-1.5 border-b border-zinc-200/50 dark:border-zinc-800">
                <span className="text-zinc-500">Application ID</span>
                <span className="font-mono font-semibold">com.rbapps.doorago</span>
              </div>
              <div className="flex justify-between py-1.5 border-b border-zinc-200/50 dark:border-zinc-800">
                <span className="text-zinc-500">Target AI Model</span>
                <span className="font-semibold">MobileActions-270M (FunctionGemma)</span>
              </div>
              <div className="flex justify-between py-1.5 border-b border-zinc-200/50 dark:border-zinc-800">
                <span className="text-zinc-500">Model File</span>
                <span className="font-mono text-[11px] font-semibold">mobile-actions_q8_ekv1024.litertlm</span>
              </div>
              <div className="flex justify-between py-1.5 border-b border-zinc-200/50 dark:border-zinc-800">
                <span className="text-zinc-500">Inference Runtime</span>
                <span className="font-semibold">LiteRT-LM 0.16.1 (On-Device)</span>
              </div>
              <div className="flex justify-between py-1.5 border-b border-zinc-200/50 dark:border-zinc-800">
                <span className="text-zinc-500">Android SDK</span>
                <span className="font-semibold">SDK 36 (minSdk 26)</span>
              </div>
              <div className="flex justify-between py-1.5 border-b border-zinc-200/50 dark:border-zinc-800">
                <span className="text-zinc-500">Engine Status</span>
                <span className="font-semibold text-emerald-600 dark:text-emerald-400 uppercase">{engineStatus}</span>
              </div>
            </div>

            <div className={`p-3 rounded-xl text-[11px] mb-5 ${
              isDarkMode ? 'bg-[#1A2120] text-zinc-300' : 'bg-zinc-100 text-zinc-700'
            }`}>
              Stage 2 integrates Google AI Edge LiteRT-LM (<code className="text-teal-500">com.google.ai.edge.litertlm:litertlm-android:0.16.1</code>) and connects Kotlin <code className="text-teal-500">LiteRtLmAiEngine</code> with Flutter <code className="text-teal-500">OfflineAiService</code>. Fully offline on-device inference with 0% network or cloud calls.
            </div>

            <button
              onClick={() => setShowSpecsModal(false)}
              className="w-full py-2.5 rounded-xl bg-teal-600 hover:bg-teal-500 text-white font-semibold text-xs"
            >
              Close
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
