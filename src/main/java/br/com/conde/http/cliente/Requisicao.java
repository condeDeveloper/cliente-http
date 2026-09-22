package br.com.conde.http.cliente;

import br.com.conde.http.mensagem.Cabecalhos;
import java.nio.charset.StandardCharsets;

/** Uma requisição a enviar. */
public final class Requisicao {

  private final String metodo;
  private final String url;
  private final Cabecalhos cabecalhos;
  private final byte[] corpo;

  private Requisicao(String metodo, String url, Cabecalhos cabecalhos, byte[] corpo) {
    this.metodo = metodo;
    this.url = url;
    this.cabecalhos = cabecalhos;
    this.corpo = corpo;
  }

  public static Requisicao obter(String url) {
    return new Requisicao("GET", url, new Cabecalhos(), new byte[0]);
  }

  public static Requisicao cabeca(String url) {
    return new Requisicao("HEAD", url, new Cabecalhos(), new byte[0]);
  }

  public static Requisicao postar(String url, String corpo, String tipo) {
    Cabecalhos cabecalhos = new Cabecalhos();

    // O charset vai declarado de propósito: sem ele o servidor supõe, e o
    // padrão do HTTP é latin-1, não UTF-8.
    cabecalhos.definir("Content-Type", tipo.contains("charset") ? tipo : tipo + "; charset=utf-8");

    return new Requisicao("POST", url, cabecalhos, corpo.getBytes(StandardCharsets.UTF_8));
  }

  public static Requisicao com(String metodo, String url, byte[] corpo) {
    return new Requisicao(metodo, url, new Cabecalhos(), corpo == null ? new byte[0] : corpo);
  }

  public String metodo() {
    return metodo;
  }

  public String url() {
    return url;
  }

  public Cabecalhos cabecalhos() {
    return cabecalhos;
  }

  public byte[] corpo() {
    return corpo;
  }

  /** Acrescenta um cabeçalho. */
  public Requisicao com(String nome, String valor) {
    cabecalhos.definir(nome, valor);

    return this;
  }

  /** Copia os cabeçalhos de outra requisição. */
  public Requisicao comCabecalhos(Cabecalhos outros) {
    for (String nome : outros.nomes()) {
      for (String valor : outros.todos(nome)) {
        cabecalhos.definir(nome, valor);
      }
    }

    return this;
  }

  /** A mesma requisição apontando para outra URL, usada no redirecionamento. */
  public Requisicao paraUrl(String outraUrl) {
    return new Requisicao(metodo, outraUrl, cabecalhos, corpo);
  }

  @Override
  public String toString() {
    return metodo + " " + url;
  }
}
