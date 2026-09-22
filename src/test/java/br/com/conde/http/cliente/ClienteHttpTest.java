package br.com.conde.http.cliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.conde.http.mensagem.Cabecalhos;
import br.com.conde.http.mensagem.ErroDeHttp;
import br.com.conde.http.mensagem.Resposta;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Os juízes destes testes são dois, e nenhum é meu.
 *
 * <p>Do lado do servidor, o {@link HttpServer} que vem no JDK: se ele entende
 * a requisição montada aqui, ela está conforme. Do lado do cliente, o
 * {@link java.net.http.HttpClient} do JDK 11+: as duas implementações
 * respondem às mesmas rotas e os resultados têm que bater.
 */
@DisplayName("cliente HTTP/1.1 sobre Socket")
class ClienteHttpTest {

  private static HttpServer servidor;
  private static String base;

  @BeforeAll
  static void subirServidor() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

    rota("/ola", troca -> responder(troca, 200, "olá, mundo", "text/plain; charset=utf-8"));

    rota("/vazio", troca -> responder(troca, 204, "", "text/plain"));

    rota(
        "/grande",
        troca -> responder(troca, 200, "x".repeat(100_000), "text/plain; charset=utf-8"));

    rota(
        "/eco",
        troca -> {
          byte[] corpo = troca.getRequestBody().readAllBytes();

          troca.getResponseHeaders().add("X-Metodo", troca.getRequestMethod());
          responderBytes(troca, 200, corpo, "application/octet-stream");
        });

    rota(
        "/cabecalhos",
        troca -> {
          StringBuilder texto = new StringBuilder();

          troca.getRequestHeaders()
              .forEach((nome, valores) -> texto.append(nome).append('=').append(String.join(",", valores)).append('\n'));

          responder(troca, 200, texto.toString(), "text/plain; charset=utf-8");
        });

    rota(
        "/varios-cookies",
        troca -> {
          troca.getResponseHeaders().add("Set-Cookie", "a=1");
          troca.getResponseHeaders().add("Set-Cookie", "b=2");
          responder(troca, 200, "ok", "text/plain");
        });

    rota(
        "/gzip",
        troca -> {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();

          try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write("conteúdo comprimido com acento".getBytes(StandardCharsets.UTF_8));
          }

          troca.getResponseHeaders().add("Content-Encoding", "gzip");
          responderBytes(troca, 200, bytes.toByteArray(), "text/plain; charset=utf-8");
        });

    rota(
        "/redireciona",
        troca -> {
          troca.getResponseHeaders().add("Location", "/ola");
          troca.sendResponseHeaders(302, -1);
          troca.close();
        });

    rota(
        "/redireciona-relativo",
        troca -> {
          troca.getResponseHeaders().add("Location", "ola");
          troca.sendResponseHeaders(302, -1);
          troca.close();
        });

    rota(
        "/laco",
        troca -> {
          troca.getResponseHeaders().add("Location", "/laco");
          troca.sendResponseHeaders(302, -1);
          troca.close();
        });

    rota("/erro", troca -> responder(troca, 500, "quebrou", "text/plain"));

    rota("/latin", troca -> responderBytes(troca, 200, "ação".getBytes(StandardCharsets.ISO_8859_1), "text/plain; charset=iso-8859-1"));

    servidor.setExecutor(Executors.newFixedThreadPool(4));
    servidor.start();

    base = "http://127.0.0.1:" + servidor.getAddress().getPort();
  }

  @AfterAll
  static void derrubarServidor() {
    servidor.stop(0);
  }

  private static void rota(String caminho, Tratador tratador) {
    servidor.createContext(
        caminho,
        troca -> {
          try {
            tratador.tratar(troca);
          } catch (IOException erro) {
            troca.close();
          }
        });
  }

  @FunctionalInterface
  private interface Tratador {
    void tratar(HttpExchange troca) throws IOException;
  }

  private static void responder(HttpExchange troca, int status, String corpo, String tipo) throws IOException {
    responderBytes(troca, status, corpo.getBytes(StandardCharsets.UTF_8), tipo);
  }

  private static void responderBytes(HttpExchange troca, int status, byte[] corpo, String tipo) throws IOException {
    troca.getResponseHeaders().add("Content-Type", tipo);

    if (status == 204 || corpo.length == 0) {
      troca.sendResponseHeaders(status, -1);
      troca.close();

      return;
    }

    troca.sendResponseHeaders(status, corpo.length);

    try (OutputStream saida = troca.getResponseBody()) {
      saida.write(corpo);
    }
  }

  @Nested
  @DisplayName("contra o HttpServer do JDK")
  class ContraOServidor {

    @Test
    @DisplayName("um GET simples volta com status, corpo e tipo")
    void getSimples() throws IOException {
      Resposta resposta = ClienteHttp.padrao().obter(base + "/ola");

      assertThat(resposta.status()).isEqualTo(200);
      assertThat(resposta.ok()).isTrue();
      assertThat(resposta.texto()).isEqualTo("olá, mundo");
      assertThat(resposta.cabecalho("Content-Type")).contains("text/plain; charset=utf-8");
    }

    @Test
    @DisplayName("o Host obrigatório do HTTP/1.1 é enviado")
    void mandaHost() throws IOException {
      // Sem ele o servidor não sabe qual dos mil sites daquele IP você quer,
      // e responde 400.
      Resposta resposta = ClienteHttp.padrao().obter(base + "/cabecalhos");

      assertThat(resposta.texto()).contains("Host=127.0.0.1:" + servidor.getAddress().getPort());
    }

    @Test
    @DisplayName("um corpo de 100 KB chega inteiro")
    void corpoGrande() throws IOException {
      // Aqui o TCP com certeza parte em vários pacotes: é o teste do laço de
      // leitura, que precisa insistir até completar o Content-Length.
      Resposta resposta = ClienteHttp.padrao().obter(base + "/grande");

      assertThat(resposta.tamanho()).isEqualTo(100_000);
      assertThat(resposta.texto()).hasSize(100_000);
    }

    @Test
    @DisplayName("204 não tem corpo e não trava esperando um")
    void semCorpo() throws IOException {
      Resposta resposta = ClienteHttp.construtor().prazoDeLeitura(2000).montar().obter(base + "/vazio");

      assertThat(resposta.status()).isEqualTo(204);
      assertThat(resposta.corpo()).isEmpty();
    }

    @Test
    @DisplayName("HEAD não lê corpo, mesmo com Content-Length declarado")
    void head() throws IOException {
      Resposta resposta = ClienteHttp.construtor().prazoDeLeitura(2000).montar().enviar(Requisicao.cabeca(base + "/ola"));

      assertThat(resposta.status()).isEqualTo(200);
      assertThat(resposta.corpo()).isEmpty();
    }

    @Test
    @DisplayName("POST manda o corpo e o Content-Length")
    void post() throws IOException {
      Resposta resposta = ClienteHttp.padrao().postar(base + "/eco", "corpo com ação", "text/plain");

      assertThat(resposta.cabecalho("X-Metodo")).contains("POST");
      assertThat(new String(resposta.corpo(), StandardCharsets.UTF_8)).isEqualTo("corpo com ação");
    }

    @Test
    @DisplayName("o gzip é desfeito sem ninguém pedir")
    void gzip() throws IOException {
      Resposta resposta = ClienteHttp.padrao().obter(base + "/gzip");

      assertThat(resposta.texto()).isEqualTo("conteúdo comprimido com acento");
    }

    @Test
    @DisplayName("o charset declarado é respeitado, e não chutado para UTF-8")
    void charsetDeclarado() throws IOException {
      // Decodificar em UTF-8 por reflexo é como se perde acento numa resposta
      // latin-1.
      Resposta resposta = ClienteHttp.padrao().obter(base + "/latin");

      assertThat(resposta.charset()).isEqualTo(StandardCharsets.ISO_8859_1);
      assertThat(resposta.texto()).isEqualTo("ação");
    }

    @Test
    @DisplayName("vários Set-Cookie não viram um só")
    void variosCookies() throws IOException {
      Resposta resposta = ClienteHttp.padrao().obter(base + "/varios-cookies");

      assertThat(resposta.cabecalhos().todos("set-cookie")).containsExactlyInAnyOrder("a=1", "b=2");
    }

    @Test
    @DisplayName("erro do servidor volta como resposta, não como exceção")
    void erroDoServidor() throws IOException {
      Resposta resposta = ClienteHttp.padrao().obter(base + "/erro");

      assertThat(resposta.status()).isEqualTo(500);
      assertThat(resposta.ok()).isFalse();
      assertThat(resposta.texto()).isEqualTo("quebrou");
    }
  }

  @Nested
  @DisplayName("redirecionamento")
  class Redirecionamento {

    @Test
    @DisplayName("é seguido e o caminho fica registrado")
    void segue() throws IOException {
      Resposta resposta = ClienteHttp.padrao().obter(base + "/redireciona");

      assertThat(resposta.status()).isEqualTo(200);
      assertThat(resposta.texto()).isEqualTo("olá, mundo");
      assertThat(resposta.redirecionamentos()).containsExactly(base + "/ola");
    }

    @Test
    @DisplayName("Location relativo é resolvido contra a URL atual")
    void relativo() throws IOException {
      Resposta resposta = ClienteHttp.padrao().obter(base + "/redireciona-relativo");

      assertThat(resposta.texto()).isEqualTo("olá, mundo");
    }

    @Test
    @DisplayName("o laço infinito tem teto")
    void laco() {
      // Um servidor mal configurado pode apontar para si mesmo; sem teto, o
      // cliente fica ali para sempre.
      assertThatThrownBy(() -> ClienteHttp.construtor().maximoDeRedirecionamentos(3).montar().obter(base + "/laco"))
          .isInstanceOf(ErroDeHttp.class)
          .hasMessageContaining("Mais de 3 redirecionamentos");
    }

    @Test
    @DisplayName("com o seguimento desligado, o 302 volta como está")
    void semSeguir() throws IOException {
      Resposta resposta = ClienteHttp.construtor().maximoDeRedirecionamentos(0).montar().obter(base + "/ola");

      assertThat(resposta.status()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("contra o HttpClient do JDK")
  class ContraOClienteDoJdk {

    @Test
    @DisplayName("as duas implementações veem a mesma resposta")
    void mesmaResposta() throws Exception {
      // Se o cliente escrito aqui diverge do que vem no JDK falando com o
      // mesmo servidor, é este que está errado.
      java.net.http.HttpClient doJdk = java.net.http.HttpClient.newHttpClient();

      for (String caminho : List.of("/ola", "/grande", "/gzip", "/erro", "/latin")) {
        Resposta nossa = ClienteHttp.padrao().obter(base + caminho);

        HttpResponse<byte[]> dele =
            doJdk.send(
                HttpRequest.newBuilder(URI.create(base + caminho)).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(nossa.status()).as("status de %s", caminho).isEqualTo(dele.statusCode());

        // O do JDK não desfaz gzip sozinho; nesse caso comparamos o texto já
        // descomprimido dos dois lados.
        if (caminho.equals("/gzip")) {
          assertThat(nossa.texto()).isEqualTo("conteúdo comprimido com acento");
          continue;
        }

        assertThat(nossa.corpo()).as("corpo de %s", caminho).isEqualTo(dele.body());
      }
    }

    @Test
    @DisplayName("o POST chega igual pelos dois")
    void postIgual() throws Exception {
      java.net.http.HttpClient doJdk = java.net.http.HttpClient.newHttpClient();
      String corpo = "dados com acentuação e / e ?";

      Resposta nossa = ClienteHttp.padrao().postar(base + "/eco", corpo, "text/plain");

      HttpResponse<byte[]> dele =
          doJdk.send(
              HttpRequest.newBuilder(URI.create(base + "/eco"))
                  .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertThat(nossa.corpo()).isEqualTo(dele.body());
    }
  }

  @Nested
  @DisplayName("resposta em pedaços")
  class EmPedacos {

    private static final String FIM_DE_LINHA = "\r\n";

    private static final String CABECALHOS_EM_PEDACOS =
        "HTTP/1.1 200 OK"
            + FIM_DE_LINHA
            + "Content-Type: text/plain; charset=utf-8"
            + FIM_DE_LINHA
            + "Transfer-Encoding: chunked"
            + FIM_DE_LINHA
            + FIM_DE_LINHA;

    /** Sobe um servidor que responde exatamente estes bytes e roda a asserção. */
    private void comServidorCru(byte[] resposta, java.util.function.Consumer<String> comUrl) throws Exception {
      try (ServerSocket servidorCru = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
        Thread atendente =
            new Thread(
                () -> {
                  try (Socket conexao = servidorCru.accept()) {
                    lerAteLinhaEmBranco(conexao);

                    conexao.getOutputStream().write(resposta);
                    conexao.getOutputStream().flush();
                  } catch (IOException ignorado) {
                    // A conexão caiu; a asserção vai reclamar sozinha.
                  }
                });

        atendente.setDaemon(true);
        atendente.start();

        comUrl.accept("http://127.0.0.1:" + servidorCru.getLocalPort() + "/");
        atendente.join(5000);
      }
    }

    /** Consome a requisição até a linha em branco que fecha os cabeçalhos. */
    private void lerAteLinhaEmBranco(Socket conexao) throws IOException {
      var entrada = conexao.getInputStream();
      int seguidos = 0;

      for (int b; (b = entrada.read()) >= 0; ) {
        if (b == '\n') {
          seguidos += 1;

          if (seguidos == 2) {
            return;
          }
        } else if (b != '\r') {
          seguidos = 0;
        }
      }
    }

    /**
     * Monta uma resposta em pedaços, com o tamanho em <b>bytes</b>.
     *
     * <p>É o erro clássico de quem escreve isso à mão: {@code "olá"} tem 3
     * caracteres e 4 bytes em UTF-8, e declarar 3 parte a mensagem no lugar
     * errado.
     */
    private byte[] emPedacos(String... pedacos) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();

      bytes.write(CABECALHOS_EM_PEDACOS.getBytes(StandardCharsets.ISO_8859_1));

      for (String pedaco : pedacos) {
        byte[] corpo = pedaco.getBytes(StandardCharsets.UTF_8);

        bytes.write((Integer.toHexString(corpo.length) + FIM_DE_LINHA).getBytes(StandardCharsets.ISO_8859_1));
        bytes.write(corpo);
        bytes.write(FIM_DE_LINHA.getBytes(StandardCharsets.ISO_8859_1));
      }

      bytes.write(("0" + FIM_DE_LINHA + FIM_DE_LINHA).getBytes(StandardCharsets.ISO_8859_1));

      return bytes.toByteArray();
    }

    /** Roda a asserção contra um servidor que responde estes bytes. */
    private void conferir(byte[] resposta, String esperado) throws Exception {
      comServidorCru(
          resposta,
          url -> {
            try {
              assertThat(ClienteHttp.padrao().obter(url).texto()).isEqualTo(esperado);
            } catch (IOException erro) {
              throw new AssertionError(erro);
            }
          });
    }

    @Test
    @DisplayName("o corpo em pedaços é remontado")
    void remonta() throws Exception {
      // É o formato que permite responder algo gerado na hora, sem saber o
      // tamanho antes de começar.
      conferir(emPedacos("olá, ", "mundo"), "olá, mundo");
    }

    @Test
    @DisplayName("um caractere partido entre dois pedaços sobrevive")
    void caracterePartido() throws Exception {
      // Cada byte do "ç" e do "ã" cai num pedaço diferente. Quem decodifica
      // pedaço por pedaço na chegada estraga o caractere; quem remonta os
      // bytes primeiro e decodifica no fim, não. É por isso que o corpo fica
      // como bytes até o último momento.
      byte[] utf8 = "ação".getBytes(StandardCharsets.UTF_8);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();

      bytes.write(CABECALHOS_EM_PEDACOS.getBytes(StandardCharsets.ISO_8859_1));

      for (int i = 0; i < utf8.length; i += 1) {
        bytes.write(("1" + FIM_DE_LINHA).getBytes(StandardCharsets.ISO_8859_1));
        bytes.write(utf8, i, 1);
        bytes.write(FIM_DE_LINHA.getBytes(StandardCharsets.ISO_8859_1));
      }

      bytes.write(("0" + FIM_DE_LINHA + FIM_DE_LINHA).getBytes(StandardCharsets.ISO_8859_1));

      conferir(bytes.toByteArray(), "ação");
    }

    @Test
    @DisplayName("extensão no tamanho do pedaço não confunde")
    void extensaoNoTamanho() throws Exception {
      // `1a;nome=valor` é um tamanho válido; quem não corta no ponto e vírgula
      // tenta ler um hexadecimal inválido.
      String resposta =
          "HTTP/1.1 200 OK"
              + FIM_DE_LINHA
              + "Transfer-Encoding: chunked"
              + FIM_DE_LINHA
              + FIM_DE_LINHA
              + "2;coisa=qualquer"
              + FIM_DE_LINHA
              + "oi"
              + FIM_DE_LINHA
              + "0"
              + FIM_DE_LINHA
              + FIM_DE_LINHA;

      conferir(resposta.getBytes(StandardCharsets.ISO_8859_1), "oi");
    }

    @Test
    @DisplayName("resposta sem tamanho e sem pedaços acaba quando a conexão fecha")
    void semTamanho() throws Exception {
      // É o jeito do HTTP/1.0, e o motivo de ele não ter conexão reaproveitada.
      String resposta =
          "HTTP/1.1 200 OK"
              + FIM_DE_LINHA
              + "Content-Type: text/plain"
              + FIM_DE_LINHA
              + FIM_DE_LINHA
              + "sem tamanho declarado";

      conferir(resposta.getBytes(StandardCharsets.ISO_8859_1), "sem tamanho declarado");
    }

    @Test
    @DisplayName("linha de status estranha é recusada com clareza")
    void statusEstranho() throws Exception {
      comServidorCru(
          ("não sou HTTP" + FIM_DE_LINHA + FIM_DE_LINHA).getBytes(StandardCharsets.ISO_8859_1),
          url ->
              assertThatThrownBy(() -> ClienteHttp.padrao().obter(url))
                  .isInstanceOf(ErroDeHttp.class)
                  .hasMessageContaining("Linha de status"));
    }
  }

  @Nested
  @DisplayName("montagem da requisição")
  class Montagem {

    @Test
    @DisplayName("a requisição tem método, caminho, versão e linha em branco")
    void formato() {
      String texto =
          ClienteHttp.padrao()
              .montarRequisicao(Requisicao.obter("http://exemplo.com/a/b?x=1"), URI.create("http://exemplo.com/a/b?x=1"));

      assertThat(texto).startsWith("GET /a/b?x=1 HTTP/1.1\r\n");
      assertThat(texto).contains("Host: exemplo.com\r\n");
      assertThat(texto).endsWith("\r\n\r\n");
    }

    @Test
    @DisplayName("o Host traz a porta só quando ela não é a padrão")
    void hostComPorta() {
      assertThat(ClienteHttp.padrao().montarRequisicao(Requisicao.obter("http://a.com:8080/"), URI.create("http://a.com:8080/")))
          .contains("Host: a.com:8080");

      assertThat(ClienteHttp.padrao().montarRequisicao(Requisicao.obter("http://a.com/"), URI.create("http://a.com/")))
          .contains("Host: a.com\r\n");

      assertThat(ClienteHttp.padrao().montarRequisicao(Requisicao.obter("https://a.com:443/"), URI.create("https://a.com:443/")))
          .contains("Host: a.com\r\n");
    }

    @Test
    @DisplayName("URL sem caminho vira barra")
    void caminhoVazio() {
      assertThat(ClienteHttp.padrao().montarRequisicao(Requisicao.obter("http://a.com"), URI.create("http://a.com")))
          .startsWith("GET / HTTP/1.1");
    }

    @Test
    @DisplayName("esquema que não é http nem https é recusado")
    void esquemaInvalido() {
      assertThatThrownBy(() -> ClienteHttp.padrao().obter("ftp://exemplo.com/a"))
          .isInstanceOf(ErroDeHttp.class)
          .hasMessageContaining("Só http e https");

      assertThatThrownBy(() -> ClienteHttp.padrao().obter("http:///sem-maquina"))
          .isInstanceOf(ErroDeHttp.class);
    }
  }

  @Nested
  @DisplayName("cabeçalhos")
  class DosCabecalhos {

    @Test
    @DisplayName("o nome não diferencia maiúscula de minúscula")
    void semDiferencaDeCaixa() {
      Cabecalhos cabecalhos = new Cabecalhos().definir("Content-Type", "text/plain");

      assertThat(cabecalhos.primeiro("content-type")).contains("text/plain");
      assertThat(cabecalhos.primeiro("CONTENT-TYPE")).contains("text/plain");
      assertThat(cabecalhos.tem("Content-type")).isTrue();
    }

    @Test
    @DisplayName("o mesmo nome pode aparecer várias vezes")
    void repetidos() {
      Cabecalhos cabecalhos = new Cabecalhos().acrescentar("Set-Cookie", "a=1").acrescentar("set-cookie", "b=2");

      assertThat(cabecalhos.todos("Set-Cookie")).containsExactly("a=1", "b=2");
      assertThat(cabecalhos.quantidade()).isEqualTo(2);
    }

    @Test
    @DisplayName("definir substitui e acrescentar soma")
    void definirESubstituir() {
      Cabecalhos cabecalhos = new Cabecalhos().acrescentar("A", "1").acrescentar("A", "2");

      assertThat(cabecalhos.todos("A")).hasSize(2);

      cabecalhos.definir("A", "3");

      assertThat(cabecalhos.todos("A")).containsExactly("3");

      cabecalhos.remover("A");

      assertThat(cabecalhos.tem("A")).isFalse();
    }

    @Test
    @DisplayName("só o primeiro dois-pontos separa")
    void primeiroDoisPontos() {
      // Um Location com https:// tem outro logo ali; cortar no último quebra
      // a URL.
      assertThat(Cabecalhos.partir("Location: https://exemplo.com:8080/a"))
          .containsExactly("Location", "https://exemplo.com:8080/a");
    }

    @Test
    @DisplayName("a grafia original é preservada na saída")
    void grafiaOriginal() {
      assertThat(new Cabecalhos().definir("X-Meu-Cabeçalho", "v").comoTexto()).isEqualTo("X-Meu-Cabeçalho: v\r\n");
    }

    @Test
    @DisplayName("linha sem nome é recusada")
    void semNome() {
      assertThatThrownBy(() -> Cabecalhos.partir("sem dois pontos")).isInstanceOf(ErroDeHttp.class);
      assertThatThrownBy(() -> Cabecalhos.partir(": só valor")).isInstanceOf(ErroDeHttp.class);
    }
  }
}
