// ============================================================================
//  CONFIGURATION FIREBASE
//  1. Console Firebase → ⚙ Paramètres du projet → « Vos applications » → </> (Web)
//  2. Copiez l'objet firebaseConfig affiché et collez-le ci-dessous à la place de null.
//
//  Tant que la valeur est null, l'application fonctionne en MODE LOCAL :
//  les données restent dans le navigateur de chaque ordinateur (pas de partage).
// ============================================================================
export const firebaseConfig = null;

/* Exemple :
export const firebaseConfig = {
  apiKey: "AIza...",
  authDomain: "mon-projet.firebaseapp.com",
  projectId: "mon-projet",
  storageBucket: "mon-projet.appspot.com",
  messagingSenderId: "123456789",
  appId: "1:123456789:web:abcdef",
};
*/

// Nom du document Firestore qui contient le programme (partagé par toute l'équipe)
export const DOCUMENT_PROGRAMME = "programmes/principal";
