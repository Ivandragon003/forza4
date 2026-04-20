#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <signal.h>
#include <sys/time.h>
#ifdef __linux__
#include <netinet/tcp.h>
#endif

#include "network.h"
#include "session.h"

static int imposta_opzione_socket(int socket_fd,
                                  int level,
                                  int optname,
                                  const void* value,
                                  socklen_t value_len,
                                  const char* errore) {
    if (setsockopt(socket_fd, level, optname, value, value_len) < 0) {
        perror(errore);
        return -1;
    }
    return 0;
}

static void configura_socket_client(int socket_client) {
    int keepalive = 1;
    (void)imposta_opzione_socket(socket_client, SOL_SOCKET, SO_KEEPALIVE,
                                 &keepalive, sizeof(keepalive),
                                 "Errore setsockopt SO_KEEPALIVE");

#ifdef TCP_KEEPIDLE  //dopo quanti secondi iniziare i controlli
    {
        int keepidle = 10;
        (void)imposta_opzione_socket(socket_client, IPPROTO_TCP, TCP_KEEPIDLE,
                                     &keepidle, sizeof(keepidle),
                                     "Errore setsockopt TCP_KEEPIDLE");
    }
#endif
#ifdef TCP_KEEPINTVL     //ogni quanto riprovare
    {
        int keepintvl = 5; 
        (void)imposta_opzione_socket(socket_client, IPPROTO_TCP, TCP_KEEPINTVL,
                                     &keepintvl, sizeof(keepintvl),
                                     "Errore setsockopt TCP_KEEPINTVL");
    }
#endif
#ifdef TCP_KEEPCNT        //quante volte prima di arrendersi
    {
        int keepcnt = 2;
        (void)imposta_opzione_socket(socket_client, IPPROTO_TCP, TCP_KEEPCNT,
                                     &keepcnt, sizeof(keepcnt),
                                     "Errore setsockopt TCP_KEEPCNT");
    }
#endif
#ifdef TCP_USER_TIMEOUT    //tempo massimo per ricevere un ACK
    {
        int timeout_ms = 15000;
        (void)imposta_opzione_socket(socket_client, IPPROTO_TCP, TCP_USER_TIMEOUT,
                                     &timeout_ms, sizeof(timeout_ms),
                                     "Errore setsockopt TCP_USER_TIMEOUT");
    }
#endif
    {
        struct timeval tv;
        tv.tv_sec = 15;
        tv.tv_usec = 0;
        (void)imposta_opzione_socket(socket_client, SOL_SOCKET, SO_RCVTIMEO,
                                     &tv, sizeof(tv),
                                     "Errore setsockopt SO_RCVTIMEO");
    }
}

int main() {
    int server_fd;
    struct sockaddr_in indirizzo;
    socklen_t addrlen = sizeof(indirizzo);
    signal(SIGPIPE, SIG_IGN);

    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
        perror("Errore creazione socket");
        exit(EXIT_FAILURE);
    }

    int opt = 1;
    if (imposta_opzione_socket(server_fd, SOL_SOCKET, SO_REUSEADDR,
                               &opt, sizeof(opt),
                               "Errore setsockopt SO_REUSEADDR") < 0) {
        exit(EXIT_FAILURE);
    }

    indirizzo.sin_family = AF_INET;
    indirizzo.sin_addr.s_addr = INADDR_ANY;
    indirizzo.sin_port = htons(PORTA);



    if (bind(server_fd, (struct sockaddr*)&indirizzo, sizeof(indirizzo)) < 0) {
        perror("Errore bind");
        exit(EXIT_FAILURE);
    }

    if (listen(server_fd, MAX_CLIENT) < 0) {
        perror("Errore listen");
        exit(EXIT_FAILURE);
    }

    printf("Server Forza 4 avviato!\n");
    printf("In ascolto sulla porta %d...\n", PORTA);

    while (1) {
        DatiClient* dati_client = malloc(sizeof(DatiClient));
        if (dati_client == NULL) {
            perror("Errore malloc DatiClient");
            continue;
        }
        addrlen = sizeof(indirizzo);
        dati_client->socket = accept(server_fd, (struct sockaddr*)&indirizzo, &addrlen);

        if (dati_client->socket < 0) {
            perror("Errore accept");
            free(dati_client);
            continue;
        }

        configura_socket_client(dati_client->socket);
        atomic_init(&dati_client->id_partita_corrente, 0);

        pthread_t thread_id;
        if (pthread_create(&thread_id, NULL, gestisci_client, (void*)dati_client) != 0) {
            perror("Errore creazione thread");
            close(dati_client->socket);
            free(dati_client);
            continue;
        }

        pthread_detach(thread_id);
    }

    close(server_fd);
    return 0;
}
