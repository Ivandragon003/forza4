import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client {
    private static final String SERVER_HOST = "server";
    private static final int SERVER_PORT = 8080;

    private static final String RESET  = "\033[0m";
    private static final String RED    = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN  = "\033[32m";
    private static final String BLUE   = "\033[36m";
    private static final String CYAN   = "\033[96m";
    private static final String BOLD   = "\033[1m";

    
    private enum StatoConnessione { DISCONNESSA, CONNESSA, CHIUSA }

    private volatile StatoConnessione stato = StatoConnessione.DISCONNESSA;

    private Socket        socket;
    private BufferedReader in;
    private PrintWriter    out;
    private Scanner        scanner;
    private Thread         listenerThread;
    private Thread         pingThread;

    // AtomicBoolean per operazioni check-then-act thread-safe
    private final AtomicBoolean chiusuraStampata = new AtomicBoolean(false);
    private final AtomicBoolean pingAvviato      = new AtomicBoolean(false);

    public Client() {
        scanner = new Scanner(System.in);
    }

  
    private boolean connessioneAttiva() {
        return stato == StatoConnessione.CONNESSA
                && socket != null
                && !socket.isClosed();
    }

   
    private void inviaComando(String comando) {
        if (!connessioneAttiva() || out == null) {
            System.err.println("Connessione non attiva. Comando non inviato: " + comando);
            return;
        }
        out.println(comando);
    }

    public void connetti() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // Solo qui lo stato diventa CONNESSA: tutti i campi sono pronti
            stato = StatoConnessione.CONNESSA;

            System.out.println(BLUE + "Connesso al server!" + RESET);
            completaHandshakeNome();
            avviaListener();

            // AtomicBoolean: il confronto e l'impostazione sono atomici
            if (pingAvviato.compareAndSet(false, true)) {
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
            if (nome.isEmpty()) continue;
            out.println(nome);
            break;
        }
    }

  
    private void inviaDisconnessioneVolontaria() {
        try {
            inviaComando("ABBANDONA");
            inviaComando("ESCI");
            if (out != null) out.flush();
        } catch (Exception ignored) {}
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
                // readLine() ha restituito null: il server ha chiuso la connessione
                System.err.println("\nServer disconnesso.");
            } catch (IOException e) {
                if (connessioneAttiva()) {
                    System.err.println("\nConnessione persa: " + e.getMessage());
                }
            } finally {
                // Garantisce la chiusura anche se nessun altro la esegue
                chiudiConnessione();
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

   
    private void avviaPing() {
        pingThread = new Thread(() -> {
            try {
                Thread.sleep(10_000);
                while (connessioneAttiva()) {
                    Thread.sleep(5_000);
                    // inviaComando controlla internamente connessioneAttiva()
                    inviaComando("PING");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pingThread.setDaemon(true);
        pingThread.start();
    }

   
    private void gestisciMessaggioServer(String messaggio) {
        if (messaggio.trim().equals(">")) return;

        if (messaggio.startsWith(">")) {
            messaggio = messaggio.substring(1).trim();
            if (messaggio.isEmpty()) return;
        }

        String n = messaggio.toLowerCase(Locale.ROOT);

        if (n.contains("hai vinto") || n.contains("vinto per resa") || n.contains("vinto a tavolino")) {
            System.out.println(GREEN + BOLD + messaggio + RESET);
        } else if (n.contains("hai perso") || n.contains(" perso")) {
            System.out.println(RED + BOLD + messaggio + RESET);
        } else if (n.contains("tuo turno") && n.contains("scegli una colonna")) {
            System.out.println(CYAN + BOLD + messaggio + RESET);
        } else if (n.contains("aspetta il tuo turno")) {
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
        if (riga.matches("\\d\\s+\\d\\s+\\d.*")) return riga;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < riga.length(); i++) {
            char c = riga.charAt(i);
            boolean isolato = (i == 0 || riga.charAt(i - 1) == ' ')
                           && (i == riga.length() - 1 || riga.charAt(i + 1) == ' ');
            if (c == 'R' && isolato) {
                sb.append(RED).append('R').append(RESET);
            } else if (c == 'G' && isolato) {
                sb.append(YELLOW).append('G').append(RESET);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

  
    public void gioca() {
        try {
            while (connessioneAttiva()) {          // esce anche se il server cade
                String comando;
                try {
                    comando = scanner.nextLine().trim();
                } catch (NoSuchElementException | IllegalStateException e) {
                    inviaDisconnessioneVolontaria();
                    break;
                }

                if (comando.isEmpty()) continue;

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


    private synchronized void chiudiConnessione() {
        // Se già CHIUSA, non fare nulla (evita doppia chiusura)
        if (stato == StatoConnessione.CHIUSA) return;
        stato = StatoConnessione.CHIUSA;

        try {
            if (listenerThread != null) listenerThread.interrupt();
            if (pingThread     != null) pingThread.interrupt();
            if (socket != null && !socket.isClosed()) socket.close();
            if (scanner != null) scanner.close();
        } catch (IOException e) {
            System.err.println("Errore chiusura: " + e.getMessage());
        }

        // AtomicBoolean: stampa "Connessione chiusa." una sola volta
        if (chiusuraStampata.compareAndSet(false, true)) {
            System.out.println("\nConnessione chiusa.");
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        Client client = new Client();
        client.connetti();
        client.gioca();
    }
}