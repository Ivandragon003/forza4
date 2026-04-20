import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;

public class Client {
    private static final String SERVER_HOST = "server";
    private static final int SERVER_PORT = 8080;
    
    private static final String RESET = "\033[0m";
    private static final String RED = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN = "\033[32m";
    private static final String BLUE = "\033[36m";
    private static final String CYAN = "\033[96m";
    private static final String BOLD = "\033[1m";
    /*sequenze di caratteri speciali che i terminali interpretano come comandi di colore. 
    \033 è il carattere ESC in ottale. RESET riporta il colore al default dopo ogni messaggio colorato.
     */
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Scanner scanner;
    private Thread listenerThread;
    private Thread pingThread;
    private volatile boolean chiusuraStampata = false;  
    private volatile boolean pingAvviato = false;
    //garantisce che quando un thread scrive questa variabile, tutti gli altri thread vedano subito il nuovo valore

    public Client() {
        scanner = new Scanner(System.in);
    }

    private boolean connessioneAttiva() {
        return socket != null && !socket.isClosed();
    }

    private void inviaComando(String comando) {
        if (out != null) {
            out.println(comando);
        }
    }
    
    public void connetti() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT); //apre la connessione TCP verso il server
            in = new BufferedReader(new InputStreamReader(socket.getInputStream())); //per leggere righe dal server (readLine)
            out = new PrintWriter(socket.getOutputStream(), true);  //autoflush - ogni println() manda subito i dati ssenza aspttaare che il buffer si riempia
            
            System.out.println(BLUE + "Connesso al server!" + RESET);
            completaHandshakeNome();
            avviaListener();
            if (!pingAvviato) {
                pingAvviato = true;
                avviaPing();
            }
            registraShutdownHook();
            
        } catch (IOException e) {
            System.err.println("Errore di connessione: " + e.getMessage());
            System.exit(1);
        }
    }

    private void completaHandshakeNome() throws IOException {
        String riga;
        while ((riga = in.readLine()) != null) {
            System.out.println(riga);
            if (riga.toLowerCase(Locale.ROOT).contains("inserisci il tuo nome")) {
                break;
            }
        }
        if (riga == null) {
            throw new IOException("Connessione chiusa durante handshake iniziale");
        }

        while (true) {
            String nome;
            try {
                nome = scanner.nextLine().trim();
            } catch (NoSuchElementException | IllegalStateException e) {
                throw new IOException("Input utente non disponibile durante handshake iniziale", e);
            }
            if (nome.isEmpty()) {
                continue;
            }
            out.println(nome);
            break;
        }
    }

    private void inviaDisconnessioneVolontaria() {
        try {
            inviaComando("ABBANDONA");
            inviaComando("ESCI");
            if (out != null) {
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void registraShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            inviaDisconnessioneVolontaria();
            chiudiConnessione();
        }));
    }
    
    private void avviaListener() {
        listenerThread = new Thread(() -> {
            try {
                String messaggio;
                while ((messaggio = in.readLine()) != null) {
                    gestisciMessaggioServer(messaggio);
                }
            } catch (IOException e) {
                if (connessioneAttiva()) {
                    System.err.println("\nConnessione persa.");
                }
            }
        });
        listenerThread.start();
    }

    private void avviaPing() {
        pingThread = new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                return;
            }
            while (connessioneAttiva()) {
                try {
                    Thread.sleep(5000);
                    if (connessioneAttiva() && out != null) {
                        inviaComando("PING");
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {
                    break;
                }
            }
        });
        pingThread.setDaemon(true);
        pingThread.start();
    }
    
    private void gestisciMessaggioServer(String messaggio) {
    
        if (messaggio.trim().equals(">")) {
            return;
        }
        
       
        if (messaggio.startsWith(">")) {
            messaggio = messaggio.substring(1).trim();
            if (messaggio.isEmpty()) {
                return;
            }
        }
        
        
        String normalizzato = messaggio.toLowerCase(Locale.ROOT);

        if (normalizzato.contains("hai vinto") ||
            normalizzato.contains("vinto per resa") ||
            normalizzato.contains("vinto a tavolino")) {
            System.out.println(GREEN + BOLD + messaggio + RESET);
        } else if (normalizzato.contains("hai perso") ||
                   normalizzato.contains(" perso")) {
            System.out.println(RED + BOLD + messaggio + RESET);
        } else if (normalizzato.contains("tuo turno") &&
                   normalizzato.contains("scegli una colonna")) {
            System.out.println(CYAN + BOLD + messaggio + RESET);
        } else if (normalizzato.contains("aspetta il tuo turno")) {
            System.out.println(BLUE + messaggio + RESET);
        } else if (messaggio.contains("PAREGGIO")) {
            System.out.println(YELLOW + BOLD + messaggio + RESET);
        } else if (messaggio.contains("NOTIFICA")) {
            System.out.println(BLUE + BOLD + messaggio + RESET);
        } else if (eGriglia(messaggio)) {
            System.out.println(coloraGriglia(messaggio));
        } else {
            System.out.println(messaggio);
        }
    }
    
    private boolean eGriglia(String testo) {
        return testo.matches(".*[.RG]\\s+[.RG].*") || 
               testo.matches("\\d\\s+\\d\\s+\\d.*");
    }
    
    private String coloraGriglia(String riga) {
        if (riga.matches("\\d\\s+\\d\\s+\\d.*")) {
            return riga;
        }
        
    
        StringBuilder risultato = new StringBuilder();
        for (int i = 0; i < riga.length(); i++) {
            char c = riga.charAt(i);
            
            if (c == 'R' && (i == 0 || riga.charAt(i-1) == ' ') && 
                (i == riga.length()-1 || riga.charAt(i+1) == ' ')) {
                risultato.append(RED).append('R').append(RESET);
            } else if (c == 'G' && (i == 0 || riga.charAt(i-1) == ' ') && 
                       (i == riga.length()-1 || riga.charAt(i+1) == ' ')) {
                risultato.append(YELLOW).append('G').append(RESET);
            } else {
                risultato.append(c);
            }
        }
        
        return risultato.toString();
    }
    
    public void gioca() {
        try {
            while (true) {
                String comando;
                try {
                    comando = scanner.nextLine().trim();
                } catch (NoSuchElementException | IllegalStateException e) {
                    inviaDisconnessioneVolontaria();
                    break;
                }
                
                if (comando.isEmpty()) {
                    continue;
                }

                if (comando.equalsIgnoreCase("ABBANDONA")) {
                    inviaComando("ABBANDONA");
                    continue;
                }
                
                if (comando.equalsIgnoreCase("ESCI")) {
                    inviaComando("ESCI");
                    Thread.sleep(200);
                    break;
                }
                
                inviaComando(comando);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            chiudiConnessione();
        }
    }
    
    private void chiudiConnessione() { // fa terminare correttamente tutti i thread e rilascia le risorse prima di uscire
        try {
            if (listenerThread != null) {
                listenerThread.interrupt();
            }
            if (pingThread != null) {
                pingThread.interrupt();
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (scanner != null) {
                scanner.close();
            }
            if (!chiusuraStampata) {
                chiusuraStampata = true;
                System.out.println("\nConnessione chiusa.");
            }
        } catch (IOException e) {
            System.err.println("Errore chiusura: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        Client client = new Client();
        client.connetti();
        client.gioca();
    }
}


