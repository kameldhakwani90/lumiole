// Pont entre la page Lumi (un seul fichier HTML) et les modules natifs de Capacitor.
// La page lit window.LumiNatif : voix native, micro natif, barre d'état, achats.
import { TextToSpeech } from '@capacitor-community/text-to-speech';
import { SpeechRecognition } from '@capacitor-community/speech-recognition';
import { StatusBar } from '@capacitor/status-bar';
import { Purchases } from '@revenuecat/purchases-capacitor';
import { registerPlugin } from '@capacitor/core';
import { App } from '@capacitor/app';

// Verrou enfant : plugin maison (Android : épinglage de l'écran ; iPhone : Accès guidé, réglé par le parent)
const Verrou = registerPlugin('Verrou');

window.LumiNatif = { TextToSpeech, SpeechRecognition, StatusBar, Purchases, Verrou, App };
